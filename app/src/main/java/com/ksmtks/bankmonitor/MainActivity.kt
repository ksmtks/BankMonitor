package com.ksmtks.bankmonitor

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
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
import android.widget.Toast
import java.time.LocalDate

private val INK = Color.parseColor("#191919")
private val SUB = Color.parseColor("#8B8F99")
private val BG = Color.parseColor("#F4F5F7")
private val YEL = Color.parseColor("#FEE500")
private val YEL_D = Color.parseColor("#FFC800")
private val BLUE = Color.parseColor("#2F6BFF")
private val PILL = Color.parseColor("#F0F1F4")
private val WHITE = Color.WHITE

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var pages: List<View>
    private lateinit var navTabs: List<LinearLayout>
    private var currentPage = 0

    // 내역
    private sealed class Row {
        data class Head(val date: LocalDate, val total: Long) : Row()
        data class Item(val d: Deposit) : Row()
    }

    private val rows = ArrayList<Row>()
    private lateinit var historyAdapter: BaseAdapter
    private lateinit var hdMonth: TextView
    private lateinit var hdSub: TextView

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

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    // ───────── UI helpers ─────────
    private fun round(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun tv(text: String, size: Float, color: Int = INK, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun hbox() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private fun card() = vbox().apply {
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = round(WHITE, 20)
    }

    private fun cardLp() = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(6), dp(16), dp(6)) }

    private fun pill(text: String) = tv(text, 14f, INK, true).apply {
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = round(PILL, 18)
    }

    private fun button(text: String, filled: Boolean) = tv(text, 15f, INK, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = if (filled) round(YEL, 16) else round(WHITE, 16, Color.parseColor("#D9DCE2"))
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

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun pageTitle(t: String) = tv(t, 24f, INK, true).apply { setPadding(dp(20), dp(20), dp(20), dp(8)) }

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
        currentPage = i
        pages.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
        navTabs.forEachIndexed { idx, t ->
            val sel = idx == i
            (t.findViewWithTag<TextView>("icon")).alpha = if (sel) 1f else 0.35f
            (t.findViewWithTag<TextView>("label")).setTextColor(if (sel) INK else SUB)
        }
        if (i == 0) refreshHistory()
        if (i == 1) refreshReport()
        if (i == 2) refreshPerm()
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
        root.addView(head, LinearLayout.LayoutParams(-1, -2))

        historyAdapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int): Any = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getViewTypeCount() = 2
            override fun getItemViewType(position: Int) = if (rows[position] is Row.Head) 0 else 1
            override fun isEnabled(position: Int) = rows[position] is Row.Item
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
                when (val r = rows[position]) {
                    is Row.Head -> headView(r)
                    is Row.Item -> itemView(r.d)
                }
        }
        val list = ListView(this).apply {
            adapter = historyAdapter
            divider = null
            dividerHeight = 0
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setPadding(0, dp(8), 0, dp(16))
            clipToPadding = false
            setOnItemLongClickListener { _, _, pos, _ ->
                val r = rows[pos]
                if (r is Row.Item) {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("${r.d.sender} ${Fmt.won(r.d.amount)} 내역을 삭제할까요?")
                        .setPositiveButton("삭제") { _, _ ->
                            Db.get(this@MainActivity).delete(r.d.id)
                            refreshHistory(); refreshReport()
                        }
                        .setNegativeButton("취소", null)
                        .show()
                }
                true
            }
        }
        val empty = tv("아직 입금 내역이 없어요\n설정에서 알림 접근을 허용하면\n입금 알림이 자동으로 쌓여요", 14f, SUB).apply {
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
        row.addView(tv(label, 13f, SUB, true), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(tv(Fmt.won(r.total), 13f, SUB, true))
        return row
    }

    private fun itemView(d: Deposit): View {
        val row = hbox().apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = round(WHITE, 16)
        }
        val av = TextView(this).apply {
            text = d.sender.take(1)
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(INK)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#FFF3A6")) }
        }
        row.addView(av, LinearLayout.LayoutParams(dp(40), dp(40)))
        val mid = vbox().apply { setPadding(dp(12), 0, dp(8), 0) }
        mid.addView(tv(d.sender, 15f, INK, true))
        mid.addView(tv(Fmt.time(d.ts) + if (d.account.isNotEmpty()) " · ${d.account}" else "", 12f, SUB))
        row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        val right = vbox().apply { gravity = Gravity.END }
        right.addView(tv("+${Fmt.won(d.amount)}", 16f, BLUE, true))
        if (d.balance >= 0) right.addView(tv("잔액 ${Fmt.won(d.balance)}", 11f, SUB))
        row.addView(right)
        return FrameLayout(this).apply {
            setPadding(dp(16), dp(4), dp(16), dp(4))
            addView(row, FrameLayout.LayoutParams(-1, -2))
        }
    }

    private fun sumOf(p: Period): Pair<Long, Int> {
        val s = Report.startOf(p, LocalDate.now())
        val l = Db.get(this).between(Fmt.millis(s), Fmt.millis(Report.endExclusive(p, s)))
        return l.sumOf { it.amount } to l.size
    }

    private fun refreshHistory() {
        if (!::historyAdapter.isInitialized) return
        val (d, _) = sumOf(Period.DAY)
        val (w, _) = sumOf(Period.WEEK)
        val (m, mn) = sumOf(Period.MONTH)
        hdMonth.text = Fmt.won(m)
        hdSub.text = "${mn}건  ·  오늘 ${Fmt.won(d)}  ·  이번 주 ${Fmt.won(w)}"
        rows.clear()
        val items = Db.get(this).between(0L, Long.MAX_VALUE, 500)
        items.groupBy { Fmt.day(it.ts) }.entries.sortedByDescending { it.key }.forEach { e ->
            rows.add(Row.Head(e.key, e.value.sumOf { it.amount }))
            e.value.forEach { rows.add(Row.Item(it)) }
        }
        historyAdapter.notifyDataSetChanged()
    }

    // ───────── 보고서 ─────────
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
        val prev = tv("‹", 30f, INK, true).apply {
            setPadding(dp(20), 0, dp(20), dp(4))
            setOnClickListener { reportStart = Report.shift(period, reportStart, -1); refreshReport() }
        }
        val next = tv("›", 30f, INK, true).apply {
            setPadding(dp(20), 0, dp(20), dp(4))
            setOnClickListener { reportStart = Report.shift(period, reportStart, 1); refreshReport() }
        }
        reportTitle = tv("", 15f, INK, true).apply { gravity = Gravity.CENTER }
        nav.addView(prev)
        nav.addView(reportTitle, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(next)
        root.addView(nav, LinearLayout.LayoutParams(-1, -2))

        reportBox = vbox()
        val sv = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(reportBox)
        }
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun barRow(label: String, amount: Long, max: Long, sub: String): View {
        val box = vbox().apply { setPadding(0, dp(8), 0, dp(8)) }
        val top = hbox()
        top.addView(tv(label, 14f, INK, true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(tv(Fmt.won(amount), 14f, INK, true))
        box.addView(top)
        val track = hbox().apply { background = round(PILL, 4) }
        val fill = View(this).apply { background = round(YEL_D, 4) }
        val rest = View(this)
        val w = amount.coerceAtLeast(1).toFloat()
        val r = (max - amount).coerceAtLeast(0).toFloat()
        track.addView(fill, LinearLayout.LayoutParams(0, dp(8), w))
        track.addView(rest, LinearLayout.LayoutParams(0, dp(8), r))
        box.addView(track, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(6) })
        box.addView(tv(sub, 11f, SUB).apply { setPadding(0, dp(4), 0, 0) })
        return box
    }

    private fun refreshReport() {
        if (!::reportBox.isInitialized) return
        segs.forEachIndexed { i, t ->
            val sel = Period.values()[i] == period
            t.background = if (sel) round(WHITE, 10) else null
            t.setTextColor(if (sel) INK else SUB)
        }
        reportTitle.text = Report.title(period, reportStart)
        val end = Report.endExclusive(period, reportStart)
        val list = Db.get(this).between(Fmt.millis(reportStart), Fmt.millis(end))
        reportBox.removeAllViews()

        val total = list.sumOf { it.amount }
        val sum = card()
        sum.addView(tv("총 입금", 13f, SUB, true))
        sum.addView(tv(Fmt.won(total), 32f, INK, true).apply { setPadding(0, dp(4), 0, dp(2)) })
        sum.addView(
            tv(
                if (list.isEmpty()) "입금 내역이 없어요" else "${list.size}건  ·  건당 평균 ${Fmt.won(total / list.size)}",
                13f, SUB
            )
        )
        reportBox.addView(sum, cardLp())

        if (list.isNotEmpty()) {
            val bySender = list.groupBy { it.sender }.entries
                .sortedByDescending { e -> e.value.sumOf { it.amount } }.take(10)
            val c1 = card()
            c1.addView(tv("보낸 사람별", 14f, INK, true))
            val max1 = bySender.first().value.sumOf { it.amount }
            bySender.forEach { e -> c1.addView(barRow(e.key, e.value.sumOf { it.amount }, max1, "${e.value.size}건")) }
            reportBox.addView(c1, cardLp())

            val c2 = card()
            if (period == Period.DAY) {
                c2.addView(tv("입금 내역", 14f, INK, true))
                list.sortedBy { it.ts }.forEach { d ->
                    val r = hbox().apply { setPadding(0, dp(8), 0, dp(8)) }
                    r.addView(tv(Fmt.time(d.ts), 13f, SUB), LinearLayout.LayoutParams(dp(56), -2))
                    r.addView(tv(d.sender, 14f, INK, true), LinearLayout.LayoutParams(0, -2, 1f))
                    r.addView(tv("+${Fmt.won(d.amount)}", 14f, BLUE, true))
                    c2.addView(r)
                }
            } else {
                c2.addView(tv("일별", 14f, INK, true))
                val byDay = list.groupBy { Fmt.day(it.ts) }.toSortedMap()
                val max2 = byDay.values.maxOf { v -> v.sumOf { it.amount } }
                byDay.forEach { (d, ds) -> c2.addView(barRow(Fmt.dayShort(d), ds.sumOf { it.amount }, max2, "${ds.size}건")) }
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

    private fun divider() = View(this).apply { setBackgroundColor(Color.parseColor("#EEF0F3")) }

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
        c2.addView(settingRow("입금 즉시 알림", sw(prefs.instant) { prefs.instant = it }).apply { setPadding(0, dp(10), 0, dp(6)) })
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
        val mDay = pill("${prefs.monthlyDay}일")
        val mTime = pill(fmtMin(prefs.monthlyMin))
        mDay.setOnClickListener {
            val names = Array(28) { "${it + 1}일" }
            AlertDialog.Builder(this).setItems(names) { _, which ->
                prefs.monthlyDay = which + 1; mDay.text = names[which]; applySchedule()
            }.show()
        }
        mTime.setOnClickListener { pickTime(prefs.monthlyMin) { prefs.monthlyMin = it; mTime.text = fmtMin(it); applySchedule() } }
        c2.addView(settingRow("월간 보고서", sw(prefs.monthly) { prefs.monthly = it; applySchedule() }))
        c2.addView(settingRow("발송 일자 / 시각", mDay, mTime))

        c2.addView(
            tv(
                "발송 시각이 낮 12시 이후면 그날까지, 12시 전이면 전날까지 집계해요.\n" +
                    "예) 일간 00:10 → 전날 보고, 일간 22:00 → 당일 보고.\n" +
                    "주간은 월~일 기준이고 지정한 시점까지의 내역을 보내요.",
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
        val on = enabled.contains(packageName)
        permStatus.text = if (on) "✅ 허용됨" else "⚠️ 허용되지 않음 (입금 알림을 읽을 수 없어요)"
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val needExact = Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()
        exactBtn.visibility = if (needExact) View.VISIBLE else View.GONE
    }
}
