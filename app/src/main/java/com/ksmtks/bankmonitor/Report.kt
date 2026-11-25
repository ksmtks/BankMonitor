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
        sb.append("📊 ${p.label} 요약\n${title(p, start, upTo)}\n\n")
        if (list.isEmpty()) {
            sb.append("거래 내역이 없습니다.")
            return sb.toString()
        }
        val t = Totals(list)
        sb.append("입금  ${Fmt.won(t.dSum)} (${t.deposits.size}건)\n")
        sb.append("출금  ${Fmt.won(t.wSum)} (${t.withdrawals.size}건)\n")
        sb.append("순증감  ${Fmt.signed(t.net)}\n")

        fun byParty(title: String, items: List<Txn>) {
            if (items.isEmpty()) return
            sb.append("\n[$title]\n")
            items.groupBy { it.party }.entries
                .sortedByDescending { e -> e.value.sumOf { it.amount } }
                .take(10)
                .forEach { e -> sb.append("· ${e.key}  ${Fmt.won(e.value.sumOf { it.amount })} (${e.value.size}건)\n") }
        }
        byParty("입금 - 보낸 사람별", t.deposits)
        byParty("출금 - 받는 사람별", t.withdrawals)

        if (p == Period.DAY) {
            sb.append("\n[거래 내역]\n")
            list.sortedBy { it.ts }.forEach {
                sb.append("· ${Fmt.time(it.ts)}  ${if (it.isDeposit) "입금" else "출금"}  ${it.party}  ${Fmt.amount(it)}\n")
            }
        } else {
            sb.append("\n[일별]\n")
            list.groupBy { Fmt.day(it.ts) }.toSortedMap().forEach { (d, ds) ->
                val dt = Totals(ds)
                sb.append("· ${Fmt.dayShort(d)}  입금 ${Fmt.won(dt.dSum)} / 출금 ${Fmt.won(dt.wSum)}\n")
            }
        }
        val bal = list.firstOrNull { it.balance >= 0 }?.balance
        if (bal != null) sb.append("\n마지막 거래 시점 잔액: ${Fmt.won(bal)}")
        return sb.toString().trimEnd()
    }
}

/**
 * 보고서 발송 스케줄. 집계는 항상 발송일 당일 기준:
 * 일간=발송일, 주간=발송일이 속한 주(월~발송일), 월간=발송일이 속한 달(1일~발송일).
 * 일간: 매일 지정 시각 / 주간: 지정 요일+시각 / 월간: 지정 일자(0=말일)+시각
 */
object Scheduler {
    private const val SCHED_VER = 2
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private fun at(d: LocalDate, min: Int): ZonedDateTime = d.atTime(min / 60, min % 60).atZone(zone)

    private fun monthDay(anyDayInMonth: LocalDate, pf: Prefs): LocalDate {
        val len = anyDayInMonth.lengthOfMonth()
        val day = if (pf.monthlyDay <= 0) len else minOf(pf.monthlyDay, len)
        return anyDayInMonth.withDayOfMonth(day)
    }

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
            val t = at(monthDay(d, pf), pf.monthlyMin)
            if (t.isAfter(now)) at(monthDay(d.withDayOfMonth(1).minusMonths(1), pf), pf.monthlyMin) else t
        }
    }

    private fun nextFire(pf: Prefs, p: Period, last: ZonedDateTime): ZonedDateTime = when (p) {
        Period.DAY -> at(last.toLocalDate().plusDays(1), pf.dailyMin)
        Period.WEEK -> at(last.toLocalDate().plusWeeks(1), pf.weeklyMin)
        Period.MONTH -> at(monthDay(last.toLocalDate().withDayOfMonth(1).plusMonths(1), pf), pf.monthlyMin)
    }

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
        pf.schedVer = SCHED_VER
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
        if (pf.schedVer != SCHED_VER) { // 앱 업데이트로 스케줄 방식이 바뀐 경우: 과거분은 보내지 않고 기준만 갱신
            resetKeys(ctx)
            return true
        }
        val now = ZonedDateTime.now(zone)
        var ok = true
        for (p in Period.values()) {
            val fire = lastFire(pf, p, now)
            val key = keyOf(fire)
            val last = lastKey(pf, p)
            if (last == key) continue
            if (last.toLongOrNull() == null) { // 최초 실행: 보내지 않고 기준만 기록
                setKey(pf, p, key)
                continue
            }
            if (enabled(pf, p)) {
                val day = fire.toLocalDate()
                val text = Report.build(ctx, p, Report.startOf(p, day), day)
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
