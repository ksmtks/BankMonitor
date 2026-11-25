package com.ksmtks.bankmonitor

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.time.LocalDate

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var pages: List<View>
    private lateinit var navTabs: List<LinearLayout>

    // 내역
    private sealed class Row {
        data class Head(val date: LocalDate, val dSum: Long, val wSum: Long) : Row()
        data class Item(val t: Txn) : Row()
    }

    private val rows = ArrayList<Row>()
    private lateinit var historyAdapter: BaseAdapter
    private lateinit var hdMonth: TextView
    private lateinit var hdSub: TextView
    private lateinit var hdSub2: TextView

    // 보고서
    private var period = Period.DAY
    private var reportStart: LocalDate = LocalDate.now()
    private lateinit var reportTitle: TextView
    private lateinit var reportBox: LinearLayout
    private lateinit var segs: List<TextView>

    // 설정
    private lateinit var etToken: EditText
    private lateinit var etChat: EditText
    private lateinit var permStatus: TextView
    private lateinit var exactBtn: TextView

    // ───────── lifecycle ─────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        reportStart = Report.startOf(period, LocalDate.now())

        val root = vbox().apply { setBackgroundColor(BG) }
        val content = FrameLayout(this)
        pages = listOf(buildHistory(), buildReport(), buildSettings())
        pages.forEach { content.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(buildNav(), LinearLayout.LayoutParams(-1, -2))
        setContentView(root)

        Scheduler.kick(this)
        show(if (prefs.configured) 0 else 2)
    }

    override fun onResume() {
        super.onResume()
        refreshHistory()
        refreshReport()
        refreshPerm()
    }

    private fun buildNav(): View {
        val bar = hbox().apply { setBackgroundColor(WHITE); elevation = dp(8).toFloat() }
        val defs = listOf("🧾" to "내역", "📊" to "보고서", "⚙️" to "설정")
        navTabs = defs.mapIndexed { i, (icon, name) ->
            val t = vbox().apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                addView(tv(icon, 20f).apply { gravity = Gravity.CENTER; tag = "icon" })
                addView(tv(name, 11f, SUB, true).apply { gravity = Gravity.CENTER; tag = "label" })
                setOnClickListener { show(i) }
            }
            bar.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            t
        }
        return bar
    }

    private fun show(i: Int) {
        pages.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
        navTabs.forEachIndexed { idx, t ->
            val sel = idx == i
            t.findViewWithTag<TextView>("icon").alpha = if (sel) 1f else 0.35f
            t.findViewWithTag<TextView>("label").setTextColor(if (sel) INK else SUB)
        }
        if (i == 0) refreshHistory()
        if (i == 1) refreshReport()
        if (i == 2) refreshPerm()
    }

    private fun openDay(d: LocalDate) {
        startActivity(Intent(this, DayActivity::class.java).putExtra("day", d.toEpochDay()))
    }

    private fun openDetail(t: Txn) {
        startActivity(Intent(this, DetailActivity::class.java).putExtra("id", t.id))
    }

    // ───────── 내역 ─────────
    private fun buildHistory(): View {
        val root = vbox()
        val head = vbox().apply {
            setPadding(dp(22), dp(22), dp(22), dp(24))
            background = GradientDrawable().apply {
                setColor(YEL)
                val r = dp(28).toFloat()
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            }
        }
        head.addView(tv("입금 모니터", 15f, INK, true))
        head.addView(tv("이번 달 입금", 13f, Color.parseColor("#6B5E00")).apply { setPadding(0, dp(16), 0, 0) })
        hdMonth = tv("0원", 34f, INK, true)
        head.addView(hdMonth)
        hdSub = tv("", 13f, Color.parseColor("#4A4200")).apply { setPadding(0, dp(4), 0, 0) }
        head.addView(hdSub)
        hdSub2 = tv("", 13f, Color.parseColor("#4A4200")).apply { setPadding(0, dp(2), 0, 0) }
        head.addView(hdSub2)
        root.addView(head, LinearLayout.LayoutParams(-1, -2))

        historyAdapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int): Any = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getViewTypeCount() = 2
            override fun getItemViewType(position: Int) = if (rows[position] is Row.Head) 0 else 1
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
                when (val r = rows[position]) {
                    is Row.Head -> headView(r)
                    is Row.Item -> txnRow(r.t)
                }
        }
        val list = ListView(this).apply {
            adapter = historyAdapter
            divider = null
            dividerHeight = 0
            selector = ColorDrawable(Color.TRANSPARENT)
            setPadding(0, dp(8), 0, dp(16))
            clipToPadding = false
            setOnItemClickListener { _, _, pos, _ ->
                when (val r = rows[pos]) {
                    is Row.Head -> openDay(r.date)
                    is Row.Item -> openDetail(r.t)
                }
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                val r = rows[pos]
                if (r is Row.Item) {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("${r.t.party} ${Fmt.amount(r.t)} 내역을 삭제할까요?")
                        .setPositiveButton("삭제") { _, _ ->
                            Db.get(this@MainActivity).delete(r.t.id)
                            refreshHistory(); refreshReport()
                        }
                        .setNegativeButton("취소", null)
                        .show()
                }
                true
            }
        }
        val empty = tv("아직 거래 내역이 없어요\n설정에서 알림 접근을 허용하면\n입출금 알림이 자동으로 쌓여요", 14f, SUB).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        }
        val frame = FrameLayout(this)
        frame.addView(list, FrameLayout.LayoutParams(-1, -1))
        frame.addView(empty, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        list.emptyView = empty
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun headView(r: Row.Head): View {
        val today = LocalDate.now()
        val label = when (r.date) {
            today -> "오늘"
            today.minusDays(1) -> "어제"
            else -> Fmt.dayShort(r.date)
        }
        val row = hbox().apply { setPadding(dp(22), dp(14), dp(22), dp(4)) }
        row.addView(tv(label, 13f, INK, true), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(tv("입금 ${Fmt.won(r.dSum)}", 12f, BLUE, true))
        row.addView(tv("  출금 ${Fmt.won(r.wSum)}", 12f, RED, true))
        row.addView(tv("  ›", 13f, SUB, true))
        return row
    }

    private fun totalsOf(p: Period): Totals {
        val s = Report.startOf(p, LocalDate.now())
        return Totals(Db.get(this).between(Fmt.millis(s), Fmt.millis(Report.endExclusive(p, s))))
    }

    private fun refreshHistory() {
        if (!::historyAdapter.isInitialized) return
        val d = totalsOf(Period.DAY)
        val m = totalsOf(Period.MONTH)
        hdMonth.text = Fmt.won(m.dSum)
        hdSub.text = "출금 ${Fmt.won(m.wSum)}  ·  순증감 ${Fmt.signed(m.net)}"
        hdSub2.text = "오늘 입금 ${Fmt.won(d.dSum)}  ·  출금 ${Fmt.won(d.wSum)}"
        rows.clear()
        val items = Db.get(this).between(0L, Long.MAX_VALUE, 500)
        items.groupBy { Fmt.day(it.ts) }.entries.sortedByDescending { it.key }.forEach { e ->
            val t = Totals(e.value)
            rows.add(Row.Head(e.key, t.dSum, t.wSum))
            e.value.forEach { rows.add(Row.Item(it)) }
        }
        historyAdapter.notifyDataSetChanged()
    }

    // ───────── 보고서 ─────────
    private fun pickReportDate() {
        DatePickerDialog(
            this, android.R.style.Theme_Material_Light_Dialog_Alert,
            { _, y, m, d ->
                reportStart = Report.startOf(period, LocalDate.of(y, m + 1, d))
                refreshReport()
            },
            reportStart.year, reportStart.monthValue - 1, reportStart.dayOfMonth
        ).show()
    }

    private fun buildReport(): View {
        val root = vbox()
        root.addView(pageTitle("보고서"))

        val seg = hbox().apply {
            background = round(Color.parseColor("#E6E8EC"), 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        segs = Period.values().map { p ->
            val t = tv(p.label, 14f, INK, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener {
                    period = p
                    reportStart = Report.startOf(p, LocalDate.now())
                    refreshReport()
                }
            }
            seg.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            t
        }
        root.addView(seg, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(4), dp(16), dp(8)) })

        val nav = hbox()
        nav.addView(tv("‹", 30f, INK, true).apply {
            setPadding(dp(20), 0, dp(20), dp(4))
            setOnClickListener { reportStart = Report.shift(period, reportStart, -1); refreshReport() }
        })
        reportTitle = tv("", 15f, INK, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { pickReportDate() }
        }
        nav.addView(reportTitle, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(tv("›", 30f, INK, true).apply {
            setPadding(dp(20), 0, dp(20), dp(4))
            setOnClickListener { reportStart = Report.shift(period, reportStart, 1); refreshReport() }
        })
        root.addView(nav, LinearLayout.LayoutParams(-1, -2))

        reportBox = vbox()
        root.addView(
            ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(reportBox) },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        return root
    }

    private fun refreshReport() {
        if (!::reportBox.isInitialized) return
        segs.forEachIndexed { i, t ->
            val sel = Period.values()[i] == period
            t.background = if (sel) round(WHITE, 10) else null
            t.setTextColor(if (sel) INK else SUB)
        }
        reportTitle.text = Report.title(period, reportStart) + "  ▾"
        val end = Report.endExclusive(period, reportStart)
        val list = Db.get(this).between(Fmt.millis(reportStart), Fmt.millis(end))
        val t = Totals(list)
        reportBox.removeAllViews()
        reportBox.addView(totalsCard(list), cardLp())

        if (list.isEmpty()) {
            reportBox.addView(tv("거래 내역이 없어요", 14f, SUB).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            }, LinearLayout.LayoutParams(-1, -2))
        } else {
            fun partyCard(title: String, items: List<Txn>, color: Int) {
                if (items.isEmpty()) return
                val g = items.groupBy { it.party }.entries.sortedByDescending { e -> e.value.sumOf { it.amount } }.take(10)
                val c = card()
                c.addView(tv(title, 14f, INK, true))
                val max = g.first().value.sumOf { it.amount }
                g.forEach { e ->
                    val sum = e.value.sumOf { it.amount }
                    c.addView(barRow(e.key, Fmt.won(sum), sum, max, "${e.value.size}건", color))
                }
                reportBox.addView(c, cardLp())
            }
            partyCard("입금 - 보낸 사람별", t.deposits, BLUE)
            partyCard("출금 - 받는 사람별", t.withdrawals, RED)

            val c2 = card()
            if (period == Period.DAY) {
                c2.addView(tv("상세 내역 (눌러서 자세히 보기)", 14f, INK, true))
                list.forEach { x -> c2.addView(txnRow(x) { openDetail(x) }.apply { setPadding(0, dp(4), 0, dp(4)) }) }
            } else {
                c2.addView(tv("일별 (눌러서 그날 보기)", 14f, INK, true))
                val byDay = list.groupBy { Fmt.day(it.ts) }.toSortedMap()
                val max2 = byDay.values.maxOf { v -> Totals(v).let { it.dSum + it.wSum } }
                byDay.forEach { (d, ds) ->
                    val dt = Totals(ds)
                    c2.addView(
                        barRow(
                            Fmt.dayShort(d), Fmt.signed(dt.net), dt.dSum + dt.wSum, max2,
                            "입금 ${Fmt.won(dt.dSum)} · 출금 ${Fmt.won(dt.wSum)} · ${ds.size}건",
                            YEL_D
                        ) { openDay(d) }
                    )
                }
            }
            reportBox.addView(c2, cardLp())
        }

        val send = button("이 보고서를 텔레그램으로 보내기", true)
        send.setOnClickListener {
            if (!prefs.configured) { toast("설정 탭에서 토큰/Chat ID를 먼저 입력하세요"); return@setOnClickListener }
            val text = Report.build(this, period, reportStart)
            val token = prefs.token
            val chat = prefs.chatId
            Thread {
                val (ok, msg) = Telegram.send(token, chat, text)
                runOnUiThread { toast(if (ok) "전송했어요" else "전송 실패: $msg") }
            }.start()
        }
        reportBox.addView(send, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(10), dp(16), dp(24)) })
    }

    // ───────── 설정 ─────────
    private fun fmtMin(m: Int) = "%02d:%02d".format(m / 60, m % 60)
    private val dowNames = arrayOf("월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일")
    private fun dayLabel(d: Int) = if (d <= 0) "말일" else "${d}일"

    private fun applySchedule() {
        Scheduler.resetKeys(this)
        Scheduler.kick(this)
    }

    private fun pickTime(cur: Int, onPick: (Int) -> Unit) {
        TimePickerDialog(
            this, android.R.style.Theme_Material_Light_Dialog_Alert,
            { _, h, m -> onPick(h * 60 + m) }, cur / 60, cur % 60, true
        ).show()
    }

    private fun settingRow(label: String, vararg right: View): LinearLayout {
        val r = hbox().apply { setPadding(0, dp(6), 0, dp(6)) }
        r.addView(tv(label, 15f, INK), LinearLayout.LayoutParams(0, -2, 1f))
        right.forEachIndexed { i, v ->
            r.addView(v, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = dp(8) })
        }
        return r
    }

    private fun sw(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        isChecked = checked
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }

    private fun field(hint: String, value: String, secret: Boolean) = EditText(this).apply {
        this.hint = hint
        setText(value)
        textSize = 15f
        setSingleLine()
        setTextColor(INK)
        setHintTextColor(SUB)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = round(BG, 12)
        inputType = InputType.TYPE_CLASS_TEXT or
            (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
    }

    private fun buildSettings(): View {
        val box = vbox().apply { setPadding(0, 0, 0, dp(24)) }
        box.addView(pageTitle("설정"))

        // 텔레그램
        val c1 = card()
        c1.addView(tv("텔레그램", 14f, INK, true))
        c1.addView(tv("봇 토큰 (BotFather에서 발급)", 12f, SUB).apply { setPadding(0, dp(12), 0, dp(6)) })
        etToken = field("123456:ABC...", prefs.token, true)
        c1.addView(etToken)
        c1.addView(tv("Chat ID (개인은 숫자, 그룹은 -100...)", 12f, SUB).apply { setPadding(0, dp(12), 0, dp(6)) })
        etChat = field("123456789", prefs.chatId, false)
        c1.addView(etChat)
        val btns = hbox().apply { setPadding(0, dp(16), 0, 0) }
        val save = button("저장", true).apply {
            setOnClickListener { saveTelegram(); toast("저장했어요") }
        }
        val test = button("테스트 전송", false).apply {
            setOnClickListener {
                saveTelegram()
                val token = prefs.token
                val chat = prefs.chatId
                Thread {
                    val (ok, msg) = Telegram.send(token, chat, "✅ 입금 모니터 테스트 메시지입니다.")
                    runOnUiThread { toast(if (ok) "테스트 메시지를 보냈어요" else "실패: $msg") }
                }.start()
            }
        }
        btns.addView(save, LinearLayout.LayoutParams(0, -2, 1f))
        btns.addView(test, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        c1.addView(btns)
        box.addView(c1, cardLp())

        // 알림
        val c2 = card()
        c2.addView(tv("알림", 14f, INK, true))
        c2.addView(settingRow("입출금 즉시 알림", sw(prefs.instant) { prefs.instant = it }).apply { setPadding(0, dp(10), 0, dp(6)) })
        c2.addView(divider(), LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(6), 0, dp(6)) })

        // 일간
        val dTime = pill(fmtMin(prefs.dailyMin))
        dTime.setOnClickListener { pickTime(prefs.dailyMin) { prefs.dailyMin = it; dTime.text = fmtMin(it); applySchedule() } }
        c2.addView(settingRow("일간 보고서", sw(prefs.daily) { prefs.daily = it; applySchedule() }))
        c2.addView(settingRow("발송 시각", dTime))

        // 주간
        c2.addView(divider(), LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(8), 0, dp(6)) })
        val wDay = pill(dowNames[prefs.weeklyDow - 1])
        val wTime = pill(fmtMin(prefs.weeklyMin))
        wDay.setOnClickListener {
            AlertDialog.Builder(this).setItems(dowNames) { _, which ->
                prefs.weeklyDow = which + 1; wDay.text = dowNames[which]; applySchedule()
            }.show()
        }
        wTime.setOnClickListener { pickTime(prefs.weeklyMin) { prefs.weeklyMin = it; wTime.text = fmtMin(it); applySchedule() } }
        c2.addView(settingRow("주간 보고서", sw(prefs.weekly) { prefs.weekly = it; applySchedule() }))
        c2.addView(settingRow("발송 요일 / 시각", wDay, wTime))

        // 월간
        c2.addView(divider(), LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(8), 0, dp(6)) })
        val mDay = pill(dayLabel(prefs.monthlyDay))
        val mTime = pill(fmtMin(prefs.monthlyMin))
        mDay.setOnClickListener {
            val names = Array(29) { dayLabel(if (it == 0) 0 else it) }
            AlertDialog.Builder(this).setItems(names) { _, which ->
                prefs.monthlyDay = which; mDay.text = names[which]; applySchedule()
            }.show()
        }
        mTime.setOnClickListener { pickTime(prefs.monthlyMin) { prefs.monthlyMin = it; mTime.text = fmtMin(it); applySchedule() } }
        c2.addView(settingRow("월간 보고서", sw(prefs.monthly) { prefs.monthly = it; applySchedule() }))
        c2.addView(settingRow("발송 일자 / 시각", mDay, mTime))

        c2.addView(
            tv(
                "집계는 항상 발송일 당일 기준이에요.\n" +
                    "일간 = 그날, 주간 = 그 주 월요일~발송일, 월간 = 그 달 1일~발송일.\n" +
                    "(기본: 매일 23:55 / 일요일 23:55 / 말일 23:55)",
                12f, SUB
            ).apply { setPadding(0, dp(12), 0, 0); setLineSpacing(0f, 1.2f) }
        )
        exactBtn = button("정확한 시각 알람 허용하기", false).apply {
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 31) {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                }
            }
        }
        c2.addView(exactBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        box.addView(c2, cardLp())

        // 권한
        val c3 = card()
        c3.addView(tv("알림 접근 권한", 14f, INK, true))
        permStatus = tv("", 15f, INK).apply { setPadding(0, dp(10), 0, dp(10)) }
        c3.addView(permStatus)
        c3.addView(button("알림 접근 설정 열기", true).apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        })
        c3.addView(
            tv(
                "• 목록에서 '입금 모니터'를 켜 주세요.\n" +
                    "• Android 13 이상에서 직접 설치한 앱은 '제한된 설정'으로 막혀 있을 수 있어요. " +
                    "설정 > 앱 > 입금 모니터 > ⋮ > '제한된 설정 허용' 후 다시 켜세요.\n" +
                    "• 알림을 놓치지 않으려면 앱의 배터리 사용을 '제한 없음'으로 설정하세요.",
                12f, SUB
            ).apply { setPadding(0, dp(12), 0, 0); setLineSpacing(0f, 1.2f) }
        )
        box.addView(c3, cardLp())

        return ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(box) }
    }

    private fun saveTelegram() {
        prefs.token = etToken.text.toString()
        prefs.chatId = etChat.text.toString()
        applySchedule()
    }

    private fun refreshPerm() {
        if (!::permStatus.isInitialized) return
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        permStatus.text = if (enabled.contains(packageName)) "✅ 허용됨" else "⚠️ 허용되지 않음 (알림을 읽을 수 없어요)"
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val needExact = Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()
        exactBtn.visibility = if (needExact) View.VISIBLE else View.GONE
    }
}
