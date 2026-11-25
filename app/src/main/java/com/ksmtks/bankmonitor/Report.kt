package com.ksmtks.bankmonitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class Period(val label: String) { DAY("일간"), WEEK("주간"), MONTH("월간") }

object Report {
    fun startOf(p: Period, ref: LocalDate): LocalDate = when (p) {
        Period.DAY -> ref
        Period.WEEK -> ref.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        Period.MONTH -> ref.withDayOfMonth(1)
    }

    fun endExclusive(p: Period, start: LocalDate): LocalDate = shift(p, start, 1)

    fun shift(p: Period, start: LocalDate, n: Int): LocalDate = when (p) {
        Period.DAY -> start.plusDays(n.toLong())
        Period.WEEK -> start.plusWeeks(n.toLong())
        Period.MONTH -> start.plusMonths(n.toLong())
    }

    fun title(p: Period, start: LocalDate): String {
        val last = endExclusive(p, start).minusDays(1)
        return when (p) {
            Period.DAY -> Fmt.date(start)
            Period.WEEK -> "${Fmt.date(start)} ~ ${Fmt.date(last)}"
            Period.MONTH -> "${start.year}년 ${start.monthValue}월"
        }
    }

    fun build(ctx: Context, p: Period, start: LocalDate): String {
        val from = Fmt.millis(start)
        val to = Fmt.millis(endExclusive(p, start))
        val list = Db.get(ctx).between(from, to)
        val sb = StringBuilder()
        sb.append("📊 ${p.label} 입금 요약\n${title(p, start)}\n\n")
        if (list.isEmpty()) {
            sb.append("입금 내역이 없습니다.")
            return sb.toString()
        }
        val total = list.sumOf { it.amount }
        sb.append("총 입금: ${Fmt.won(total)} (${list.size}건)\n")
        if (p != Period.DAY) {
            val days = list.map { Fmt.day(it.ts) }.toSet().size
            sb.append("입금 발생일: ${days}일 / 건당 평균 ${Fmt.won(total / list.size)}\n")
        }

        sb.append("\n[보낸 사람별]\n")
        list.groupBy { it.sender }.entries
            .sortedByDescending { e -> e.value.sumOf { it.amount } }
            .take(10)
            .forEach { e -> sb.append("· ${e.key}  ${Fmt.won(e.value.sumOf { it.amount })} (${e.value.size}건)\n") }

        if (p == Period.DAY) {
            sb.append("\n[입금 내역]\n")
            list.sortedBy { it.ts }.forEach { sb.append("· ${Fmt.time(it.ts)}  ${it.sender}  ${Fmt.won(it.amount)}\n") }
        } else {
            sb.append("\n[일별]\n")
            list.groupBy { Fmt.day(it.ts) }.toSortedMap().forEach { (d, ds) ->
                sb.append("· ${Fmt.dayShort(d)}  ${Fmt.won(ds.sumOf { it.amount })} (${ds.size}건)\n")
            }
        }
        val bal = list.firstOrNull { it.balance >= 0 }?.balance
        if (bal != null) sb.append("\n마지막 입금 시점 잔액: ${Fmt.won(bal)}")
        return sb.toString().trimEnd()
    }
}

object Scheduler {
    /** 매일 00:10에 지난 일/주/월 보고서를 확인해서 전송한다. (정확한 시각 보장은 없는 근사 알람) */
    fun schedule(ctx: Context, retry: Boolean = false) {
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = if (retry) {
            System.currentTimeMillis() + 30 * 60_000L
        } else {
            val z = ZoneId.systemDefault()
            var t = LocalDate.now().atTime(0, 10).atZone(z)
            if (!t.toInstant().isAfter(Instant.now())) t = LocalDate.now().plusDays(1).atTime(0, 10).atZone(z)
            t.toInstant().toEpochMilli()
        }
        val pi = PendingIntent.getBroadcast(
            app, 1, Intent(app, ReportReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    /** 백그라운드 스레드에서 호출: 밀린 보고서 전송 후 다음 알람 예약. */
    fun kick(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            val ok = runDue(app)
            schedule(app, retry = !ok)
        }.start()
    }

    fun runDue(ctx: Context): Boolean {
        val pf = Prefs(ctx)
        if (!pf.configured) return true
        val today = LocalDate.now()
        var ok = true
        ok = handle(ctx, Period.DAY, today.minusDays(1), pf.lastDaily, pf.daily) { pf.lastDaily = it } && ok
        ok = handle(ctx, Period.WEEK, Report.startOf(Period.WEEK, today).minusWeeks(1), pf.lastWeekly, pf.weekly) { pf.lastWeekly = it } && ok
        ok = handle(ctx, Period.MONTH, Report.startOf(Period.MONTH, today).minusMonths(1), pf.lastMonthly, pf.monthly) { pf.lastMonthly = it } && ok
        return ok
    }

    private fun handle(
        ctx: Context, p: Period, start: LocalDate, last: String, enabled: Boolean, save: (String) -> Unit
    ): Boolean {
        val key = start.toString()
        if (last == key) return true
        if (last.isEmpty()) { // 최초 설정 시에는 과거 보고서를 보내지 않고 기준만 기록
            save(key)
            return true
        }
        if (enabled) {
            val pf = Prefs(ctx)
            val (ok, _) = Telegram.send(pf.token, pf.chatId, Report.build(ctx, p, start))
            if (!ok) return false
        }
        save(key)
        return true
    }
}

class ReportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pr = goAsync()
        Thread {
            try {
                val ok = Scheduler.runDue(app)
                Scheduler.schedule(app, retry = !ok)
            } finally {
                pr.finish()
            }
        }.start()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pr = goAsync()
        Thread {
            try {
                val ok = Scheduler.runDue(app)
                Scheduler.schedule(app, retry = !ok)
            } finally {
                pr.finish()
            }
        }.start()
    }
}
