package com.ksmtks.bankmonitor

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var content: FrameLayout
    private lateinit var pages: List<View>

    // 내역
    private lateinit var historySummary: TextView
    private val items = ArrayList<Deposit>()
    private lateinit var historyAdapter: ArrayAdapter<Deposit>

    // 보고서
    private var period = Period.DAY
    private var reportStart: LocalDate = LocalDate.now()
    private lateinit var reportTitle: TextView
    private lateinit var reportBody: TextView

    // 설정
    private lateinit var etToken: EditText
    private lateinit var etChat: EditText
    private lateinit var swInstant: Switch
    private lateinit var swDaily: Switch
    private lateinit var swWeekly: Switch
    private lateinit var swMonthly: Switch
    private lateinit var permStatus: TextView

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        reportStart = Report.startOf(period, LocalDate.now())

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        content = FrameLayout(this)
        listOf("내역", "보고서", "설정").forEachIndexed { i, n ->
            tabs.addView(
                Button(this).apply { text = n; setOnClickListener { show(i) } },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        root.addView(tabs)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        pages = listOf(buildHistory(), buildReport(), buildSettings())
        pages.forEach { content.addView(it, FrameLayout.LayoutParams(-1, -1)) }

        Scheduler.kick(this)
        show(if (prefs.configured) 0 else 2)
    }

    override fun onResume() {
        super.onResume()
        refreshHistory()
        refreshReport()
        refreshPerm()
    }

    private fun show(i: Int) {
        pages.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
        if (i == 0) refreshHistory()
        if (i == 1) refreshReport()
        if (i == 2) refreshPerm()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ───────── 내역 ─────────
    private fun buildHistory(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), 0) }
        historySummary = TextView(this).apply { textSize = 15f; setPadding(0, dp(4), 0, dp(8)) }
        box.addView(historySummary)

        historyAdapter = object : ArrayAdapter<Deposit>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, items
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val d = getItem(position)!!
                v.findViewById<TextView>(android.R.id.text1).text = "+${Fmt.won(d.amount)}   ${d.sender}"
                val bal = if (d.balance >= 0) " · 잔액 ${Fmt.won(d.balance)}" else ""
                v.findViewById<TextView>(android.R.id.text2).text = "${Fmt.dt(d.ts)}$bal"
                return v
            }
        }
        val list = ListView(this).apply {
            adapter = historyAdapter
            emptyView = null
            setOnItemLongClickListener { _, _, pos, _ ->
                val d = items[pos]
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("${d.sender} ${Fmt.won(d.amount)} 내역을 삭제할까요?")
                    .setPositiveButton("삭제") { _, _ -> Db.get(this@MainActivity).delete(d.id); refreshHistory(); refreshReport() }
                    .setNegativeButton("취소", null)
                    .show()
                true
            }
        }
        box.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        return box
    }

    private fun sumOf(p: Period): Pair<Long, Int> {
        val s = Report.startOf(p, LocalDate.now())
        val l = Db.get(this).between(Fmt.millis(s), Fmt.millis(Report.endExclusive(p, s)))
        return l.sumOf { it.amount } to l.size
    }

    private fun refreshHistory() {
        if (!::historyAdapter.isInitialized) return
        val (d, dn) = sumOf(Period.DAY)
        val (w, wn) = sumOf(Period.WEEK)
        val (m, mn) = sumOf(Period.MONTH)
        historySummary.text = "오늘  ${Fmt.won(d)} (${dn}건)\n이번 주  ${Fmt.won(w)} (${wn}건)\n이번 달  ${Fmt.won(m)} (${mn}건)\n" +
            "(항목을 길게 누르면 삭제)"
        items.clear()
        items.addAll(Db.get(this).between(0L, Long.MAX_VALUE, 500))
        historyAdapter.notifyDataSetChanged()
    }

    // ───────── 보고서 ─────────
    private fun buildReport(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }

        val pRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Period.values().forEach { p ->
            pRow.addView(
                Button(this).apply {
                    text = p.label
                    setOnClickListener {
                        period = p
                        reportStart = Report.startOf(p, LocalDate.now())
                        refreshReport()
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        box.addView(pRow)

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nav.addView(Button(this).apply {
            text = "◀"
            setOnClickListener { reportStart = Report.shift(period, reportStart, -1); refreshReport() }
        })
        reportTitle = TextView(this).apply { textSize = 16f; gravity = android.view.Gravity.CENTER; setTypeface(null, Typeface.BOLD) }
        nav.addView(reportTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        nav.addView(Button(this).apply {
            text = "▶"
            setOnClickListener { reportStart = Report.shift(period, reportStart, 1); refreshReport() }
        })
        box.addView(nav)

        reportBody = TextView(this).apply { textSize = 14f; setPadding(0, dp(8), 0, dp(8)); setTextIsSelectable(true) }
        val sv = ScrollView(this).apply { addView(reportBody) }
        box.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        box.addView(Button(this).apply {
            text = "이 보고서를 텔레그램으로 보내기"
            setOnClickListener {
                if (!prefs.configured) { toast("설정 탭에서 토큰/Chat ID를 먼저 입력하세요"); return@setOnClickListener }
                val text = Report.build(this@MainActivity, period, reportStart)
                val token = prefs.token; val chat = prefs.chatId
                Thread {
                    val (ok, msg) = Telegram.send(token, chat, text)
                    runOnUiThread { toast(if (ok) "전송했습니다" else "전송 실패: $msg") }
                }.start()
            }
        })
        return box
    }

    private fun refreshReport() {
        if (!::reportBody.isInitialized) return
        reportTitle.text = "${period.label}\n${Report.title(period, reportStart)}"
        reportBody.text = Report.build(this, period, reportStart)
    }

    // ───────── 설정 ─────────
    private fun label(t: String) = TextView(this).apply { text = t; setPadding(0, dp(12), 0, 0) }

    private fun buildSettings(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }

        box.addView(label("텔레그램 봇 토큰 (BotFather에서 발급)"))
        etToken = EditText(this).apply {
            setText(prefs.token)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        box.addView(etToken)

        box.addView(label("Chat ID (개인은 숫자, 그룹은 -100... 형태)"))
        etChat = EditText(this).apply { setText(prefs.chatId); setSingleLine() }
        box.addView(etChat)

        swInstant = Switch(this).apply { text = "입금 즉시 알림"; isChecked = prefs.instant }
        swDaily = Switch(this).apply { text = "일간 보고서 (매일 00:10, 전날)"; isChecked = prefs.daily }
        swWeekly = Switch(this).apply { text = "주간 보고서 (월요일, 지난 주)"; isChecked = prefs.weekly }
        swMonthly = Switch(this).apply { text = "월간 보고서 (1일, 지난 달)"; isChecked = prefs.monthly }
        listOf(swInstant, swDaily, swWeekly, swMonthly).forEach {
            it.setPadding(0, dp(10), 0, dp(10)); box.addView(it)
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply { text = "저장"; setOnClickListener { save(); toast("저장했습니다") } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "테스트 전송"
            setOnClickListener {
                save()
                val token = prefs.token; val chat = prefs.chatId
                Thread {
                    val (ok, msg) = Telegram.send(token, chat, "✅ 입금 모니터 테스트 메시지입니다.")
                    runOnUiThread { toast(if (ok) "테스트 메시지를 보냈습니다" else "실패: $msg") }
                }.start()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row)

        box.addView(label("알림 접근 권한"))
        permStatus = TextView(this).apply { textSize = 15f }
        box.addView(permStatus)
        box.addView(Button(this).apply {
            text = "알림 접근 설정 열기"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        })
        box.addView(TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
            text = "• 목록에서 '입금 모니터'를 켜 주세요.\n" +
                "• Android 13 이상에서 직접 설치한 앱은 '제한된 설정'으로 막혀 있을 수 있습니다. " +
                "설정 > 앱 > 입금 모니터 > ⋮ > '제한된 설정 허용' 후 다시 켜세요.\n" +
                "• 알림을 놓치지 않으려면 앱의 배터리 사용을 '제한 없음'으로 설정하세요."
        })
        return ScrollView(this).apply { addView(box) }
    }

    private fun save() {
        prefs.token = etToken.text.toString()
        prefs.chatId = etChat.text.toString()
        prefs.instant = swInstant.isChecked
        prefs.daily = swDaily.isChecked
        prefs.weekly = swWeekly.isChecked
        prefs.monthly = swMonthly.isChecked
        Scheduler.kick(this)
    }

    private fun refreshPerm() {
        if (!::permStatus.isInitialized) return
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        val on = enabled.contains(packageName)
        permStatus.text = if (on) "✅ 허용됨" else "⚠️ 허용되지 않음 (입금 알림을 읽을 수 없습니다)"
    }
}
