package com.ksmtks.bankmonitor

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate

/** 하루치 집계 + 거래 목록. 항목을 누르면 상세 화면. */
class DayActivity : Activity() {
    private var day: LocalDate = LocalDate.now()
    private lateinit var dayTitle: TextView
    private lateinit var box: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        day = LocalDate.ofEpochDay(intent.getLongExtra("day", LocalDate.now().toEpochDay()))

        val root = vbox().apply { setBackgroundColor(BG) }
        root.addView(topBar("일별 요약") { finish() })

        val nav = hbox().apply { setPadding(0, dp(8), 0, dp(4)) }
        nav.addView(tv("‹", 30f, INK, true).apply {
            setPadding(dp(20), 0, dp(20), dp(4))
            setOnClickListener { day = day.minusDays(1); refresh() }
        })
        dayTitle = tv("", 16f, INK, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { pickDate() }
        }
        nav.addView(dayTitle, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(tv("›", 30f, INK, true).apply {
            setPadding(dp(20), 0, dp(20), dp(4))
            setOnClickListener { day = day.plusDays(1); refresh() }
        })
        root.addView(nav)

        box = vbox()
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun pickDate() {
        DatePickerDialog(
            this, android.R.style.Theme_Material_Light_Dialog_Alert,
            { _, y, m, d -> day = LocalDate.of(y, m + 1, d); refresh() },
            day.year, day.monthValue - 1, day.dayOfMonth
        ).show()
    }

    private fun refresh() {
        dayTitle.text = "${Fmt.date(day)}  ▾"
        val list = Db.get(this).between(Fmt.millis(day), Fmt.millis(day.plusDays(1)))
        box.removeAllViews()
        box.addView(totalsCard(list), cardLp())
        if (list.isEmpty()) {
            box.addView(tv("이 날은 거래 내역이 없어요", 14f, SUB).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(32), 0, dp(32))
            }, LinearLayout.LayoutParams(-1, -2))
            return
        }
        box.addView(tv("상세 내역 (눌러서 자세히 보기)", 13f, SUB, true).apply { setPadding(dp(22), dp(12), dp(22), dp(4)) })
        list.forEach { t ->
            box.addView(txnRow(t) {
                startActivity(Intent(this, DetailActivity::class.java).putExtra("id", t.id))
            })
        }
        val send = button("이 날 보고서를 텔레그램으로 보내기", true)
        send.setOnClickListener {
            val pf = Prefs(this)
            if (!pf.configured) { toast("설정 탭에서 토큰/Chat ID를 먼저 입력하세요"); return@setOnClickListener }
            val text = Report.build(this, Period.DAY, day)
            Thread {
                val (ok, msg) = Telegram.send(pf.token, pf.chatId, text)
                runOnUiThread { toast(if (ok) "전송했어요" else "전송 실패: $msg") }
            }.start()
        }
        box.addView(send, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(14), dp(16), dp(24)) })
    }
}

