package com.ksmtks.bankmonitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
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

    /** upTo: 집계 마지막 날짜(포함). null이면 기간 끝까지. */
    fun title(p: Period, start: LocalDate, upTo: LocalDate? = null): String {
        val periodLast = endExclusive(p, start).minusDays(1)
        val last = if (upTo != null && upTo.isBefore(periodLast)) upTo else periodLast
        return when (p) {
            Period.DAY -> Fmt.date(start)
            Period.WEEK -> "${Fmt.date(start)} ~ ${Fmt.date(last)}"
            Period.MONTH ->
                if (last == periodLast) "${start.year}년 ${start.monthValue}월"
                else "${start.year}년 ${start.monthValue}월 (${start.dayOfMonth}일~${last.dayOfMonth}일)"
        }
    }

    fun build(ctx: Context, p: Period, start: LocalDate, upTo: LocalDate? = null): String {
        val periodEnd = endExclusive(p, start)
        val end = if (upTo != null && upTo.plusDays(1).isBefore(periodEnd)) upTo.plusDays(1) else periodEnd
        val list = Db.get(ctx).between(Fmt.millis(start), Fmt.millis(end))
        val sb = StringBuilder()
        sb.append("📊 ${p.label} 입금 요약\n${title(p, start, upTo)}\n\n")
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

/**
 * 보고서 발송 스케줄.
 * - 일간: 매일 지정 시각 / 주간: 지정 요일+시각 / 월간: 지정 일자+시각
 * - 집계 기준일: 발송 시각이 낮 12시 이후면 당일, 이전이면 전날까지.
 */
object Scheduler {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private fun at(d: LocalDate, min: Int): ZonedDateTime = d.atTime(min / 60, min % 60).atZone(zone)

    fun lastFire(pf: Prefs, p: Period, now: ZonedDateTime): ZonedDateTime = when (p) {
        Period.DAY -> {
            val t = at(now.toLocalDate(), pf.dailyMin)
            if (t.isAfter(now)) t.minusDays(1) else t
        }
        Period.WEEK -> {
            var d = now.toLocalDate()
            while (d.dayOfWeek.value != pf.weeklyDow) d = d.minusDays(1)
            val t = at(d, pf.weeklyMin)
            if (t.isAfter(now)) at(d.minusWeeks(1), pf.weeklyMin) else t
        }
        Period.MONTH -> {
            val d = now.toLocalDate()
            val t = at(d.withDayOfMonth(pf.monthlyDay), pf.monthlyMin)
            if (t.isAfter(now)) at(d.withDayOfMonth(1).minusMonths(1).withDayOfMonth(pf.monthlyDay), pf.monthlyMin) else t
        }
    }

    private fun nextFire(pf: Prefs, p: Period, last: ZonedDateTime): ZonedDateTime = when (p) {
        Period.DAY -> at(last.toLocalDate().plusDays(1), pf.dailyMin)
        Period.WEEK -> at(last.toLocalDate().plusWeeks(1), pf.weeklyMin)
        Period.MONTH -> at(last.toLocalDate().withDayOfMonth(1).plusMonths(1).withDayOfMonth(pf.monthlyDay), pf.monthlyMin)
    }

    private fun effectiveDay(fire: ZonedDateTime): LocalDate =
        if (fire.hour >= 12) fire.toLocalDate() else fire.toLocalDate().minusDays(1)

    private fun enabled(pf: Prefs, p: Period) = when (p) {
        Period.DAY -> pf.daily
        Period.WEEK -> pf.weekly
        Period.MONTH -> pf.monthly
    }

    private fun lastKey(pf: Prefs, p: Period) = when (p) {
        Period.DAY -> pf.lastDaily
        Period.WEEK -> pf.lastWeekly
        Period.MONTH -> pf.lastMonthly
    }

    private fun setKey(pf: Prefs, p: Period, v: String) {
        when (p) {
            Period.DAY -> pf.lastDaily = v
            Period.WEEK -> pf.lastWeekly = v
            Period.MONTH -> pf.lastMonthly = v
        }
    }

    private fun keyOf(f: ZonedDateTime) = f.toInstant().toEpochMilli().toString()

    /** 설정을 바꿨을 때 호출: 이미 지난 발송 시각에 대해 과거 보고서가 한꺼번에 나가지 않게 기준만 갱신. */
    fun resetKeys(ctx: Context) {
        val pf = Prefs(ctx)
        val now = ZonedDateTime.now(zone)
        Period.values().forEach { setKey(pf, it, keyOf(lastFire(pf, it, now))) }
    }

    fun schedule(ctx: Context, retry: Boolean = false) {
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            app, 1, Intent(app, ReportReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pf = Prefs(app)
        val now = ZonedDateTime.now(zone)
        var at = Long.MAX_VALUE
        if (pf.configured) {
            for (p in Period.values()) {
                if (!enabled(pf, p)) continue
                val n = nextFire(pf, p, lastFire(pf, p, now)).toInstant().toEpochMilli()
                at = minOf(at, n)
            }
        }
        if (retry) at = minOf(at, System.currentTimeMillis() + 30 * 60_000L)
        if (at == Long.MAX_VALUE) {
            am.cancel(pi)
            return
        }
        at += 2000
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    /** 백그라운드 스레드에서 밀린 보고서 전송 후 다음 알람 예약. */
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
        val now = ZonedDateTime.now(zone)
        var ok = true
        for (p in Period.values()) {
            val fire = lastFire(pf, p, now)
            val key = keyOf(fire)
            val last = lastKey(pf, p)
            if (last == key) continue
            if (last.toLongOrNull() == null) { // 최초 실행(또는 이전 버전 형식): 보내지 않고 기준만 기록
                setKey(pf, p, key)
                continue
            }
            if (enabled(pf, p)) {
                val eff = effectiveDay(fire)
                val text = Report.build(ctx, p, Report.startOf(p, eff), eff)
                val (sent, _) = Telegram.send(pf.token, pf.chatId, text)
                if (!sent) { ok = false; continue }
            }
            setKey(pf, p, key)
        }
        return ok
    }
}

class ReportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = fire(context)
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = fire(context)
}

private fun BroadcastReceiver.fire(context: Context) {
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
