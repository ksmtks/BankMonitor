package com.ksmtks.bankmonitor

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/** 거래 1건 상세. */
class DetailActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t = Db.get(this).byId(intent.getLongExtra("id", -1))
        if (t == null) { finish(); return }

        val root = vbox().apply { setBackgroundColor(BG) }
        root.addView(topBar("거래 상세") { finish() })
        val box = vbox().apply { setPadding(0, dp(8), 0, dp(24)) }

        val color = if (t.isDeposit) BLUE else RED
        val kindName = if (t.isDeposit) "입금" else "출금"
        val partyLabel = if (t.isDeposit) "보낸 사람" else "받는 사람"

        val head = card().apply { gravity = Gravity.CENTER_HORIZONTAL }
        head.addView(tv(kindName, 13f, color, true).apply {
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = round(PILL, 14)
        })
        head.addView(tv(Fmt.amount(t), 34f, color, true).apply { setPadding(0, dp(12), 0, dp(4)) })
        head.addView(tv(t.party, 15f, INK, true))
        box.addView(head, cardLp())

        val info = card()
        fun row(label: String, value: String) {
            val r = hbox().apply { setPadding(0, dp(8), 0, dp(8)) }
            r.addView(tv(label, 14f, SUB), LinearLayout.LayoutParams(dp(96), -2))
            r.addView(tv(value, 14f, INK, true).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
            info.addView(r)
        }
        row("구분", kindName)
        row("일시", Fmt.full(t.ts))
        row(partyLabel, t.party)
        if (t.account.isNotEmpty()) row("내 계좌", t.account)
        if (t.balance >= 0) row("거래 후 잔액", Fmt.won(t.balance))
        box.addView(info, cardLp())

        if (t.raw.isNotBlank()) {
            val raw = card()
            raw.addView(tv("알림 원문", 13f, SUB, true))
            raw.addView(tv(t.raw, 13f, INK).apply {
                setPadding(0, dp(8), 0, 0)
                setTextIsSelectable(true)
            })
            box.addView(raw, cardLp())
        }

        val del = button("이 내역 삭제", false, RED)
        del.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("${t.party} ${Fmt.amount(t)} 내역을 삭제할까요?")
                .setPositiveButton("삭제") { _, _ -> Db.get(this).delete(t.id); finish() }
                .setNegativeButton("취소", null)
                .show()
        }
        box.addView(del, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(14), dp(16), 0) })

        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }
}
