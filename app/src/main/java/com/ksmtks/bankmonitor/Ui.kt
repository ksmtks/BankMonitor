package com.ksmtks.bankmonitor

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

internal val INK = Color.parseColor("#191919")
internal val SUB = Color.parseColor("#8B8F99")
internal val BG = Color.parseColor("#F4F5F7")
internal val YEL = Color.parseColor("#FEE500")
internal val YEL_D = Color.parseColor("#FFC800")
internal val BLUE = Color.parseColor("#2F6BFF")
internal val RED = Color.parseColor("#E5484D")
internal val PILL = Color.parseColor("#F0F1F4")
internal val WHITE = Color.WHITE

fun Context.dp(n: Int) = (n * resources.displayMetrics.density).toInt()

fun Context.round(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(r).toFloat()
    if (stroke != 0) setStroke(dp(1), stroke)
}

fun Context.tv(text: String, size: Float, color: Int = INK, bold: Boolean = false) = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(color)
    if (bold) setTypeface(null, Typeface.BOLD)
}

fun Context.vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.hbox() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.card() = vbox().apply {
    setPadding(dp(18), dp(16), dp(18), dp(16))
    background = round(WHITE, 20)
}

fun Context.cardLp() = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(6), dp(16), dp(6)) }

fun Context.pill(text: String) = tv(text, 14f, INK, true).apply {
    setPadding(dp(14), dp(8), dp(14), dp(8))
    background = round(PILL, 18)
}

fun Context.button(text: String, filled: Boolean, textColor: Int = INK) = tv(text, 15f, textColor, true).apply {
    gravity = Gravity.CENTER
    setPadding(dp(16), dp(14), dp(16), dp(14))
    background = if (filled) round(YEL, 16) else round(WHITE, 16, Color.parseColor("#D9DCE2"))
}

fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

fun Context.pageTitle(t: String) = tv(t, 24f, INK, true).apply { setPadding(dp(20), dp(20), dp(20), dp(8)) }

fun Context.divider() = View(this).apply { setBackgroundColor(Color.parseColor("#EEF0F3")) }

fun Context.topBar(title: String, onBack: () -> Unit): LinearLayout = hbox().apply {
    setBackgroundColor(YEL)
    setPadding(dp(8), dp(10), dp(16), dp(10))
    addView(tv("‹", 28f, INK, true).apply {
        setPadding(dp(12), 0, dp(12), dp(4))
        setOnClickListener { onBack() }
    })
    addView(tv(title, 17f, INK, true))
}

/** 거래 1건 행 (카드형). */
fun Context.txnRow(t: Txn, onClick: (() -> Unit)? = null): View {
    val color = if (t.isDeposit) BLUE else RED
    val row = hbox().apply {
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = round(WHITE, 16)
    }
    val av = TextView(this).apply {
        text = t.party.take(1)
        gravity = Gravity.CENTER
        textSize = 16f
        setTextColor(INK)
        setTypeface(null, Typeface.BOLD)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (t.isDeposit) "#FFF3A6" else "#FFE1E1"))
        }
    }
    row.addView(av, LinearLayout.LayoutParams(dp(40), dp(40)))
    val mid = vbox().apply { setPadding(dp(12), 0, dp(8), 0) }
    mid.addView(tv(t.party, 15f, INK, true))
    mid.addView(tv("${if (t.isDeposit) "입금" else "출금"} · ${Fmt.time(t.ts)}", 12f, SUB))
    row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
    val right = vbox().apply { gravity = Gravity.END }
    right.addView(tv(Fmt.amount(t), 16f, color, true))
    if (t.balance >= 0) right.addView(tv("잔액 ${Fmt.won(t.balance)}", 11f, SUB))
    row.addView(right)
    return FrameLayout(this).apply {
        setPadding(dp(16), dp(4), dp(16), dp(4))
        addView(row, FrameLayout.LayoutParams(-1, -2))
        if (onClick != null) setOnClickListener { onClick() }
    }
}

/** 입금/출금/순증감 요약 카드. */
fun Context.totalsCard(list: List<Txn>): View {
    val t = Totals(list)
    val c = card()
    val row = hbox().apply { gravity = Gravity.TOP }
    fun col(label: String, color: Int, sum: Long, cnt: Int) = vbox().apply {
        addView(tv(label, 13f, SUB, true))
        addView(tv(Fmt.won(sum), 22f, color, true).apply { setPadding(0, dp(4), 0, dp(2)) })
        addView(tv("${cnt}건", 12f, SUB))
    }
    row.addView(col("입금", BLUE, t.dSum, t.deposits.size), LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(col("출금", RED, t.wSum, t.withdrawals.size), LinearLayout.LayoutParams(0, -2, 1f))
    c.addView(row)
    c.addView(divider(), LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(12), 0, dp(12)) })
    val net = hbox()
    net.addView(tv("순증감 (입금 - 출금)", 13f, SUB, true), LinearLayout.LayoutParams(0, -2, 1f))
    net.addView(tv(Fmt.signed(t.net), 16f, if (t.net >= 0) BLUE else RED, true))
    c.addView(net)
    return c
}

/** 가로 막대 행. amount/max로 막대 길이를 정한다. */
fun Context.barRow(
    label: String, valueText: String, amount: Long, max: Long, sub: String,
    color: Int = YEL_D, onClick: (() -> Unit)? = null
): View {
    val box = vbox().apply { setPadding(0, dp(8), 0, dp(8)) }
    val top = hbox()
    top.addView(tv(label, 14f, INK, true), LinearLayout.LayoutParams(0, -2, 1f))
    top.addView(tv(valueText, 14f, INK, true))
    if (onClick != null) top.addView(tv("  ›", 14f, SUB, true))
    box.addView(top)
    val track = hbox().apply { background = round(PILL, 4) }
    val w = amount.coerceAtLeast(1).toFloat()
    val r = (max - amount).coerceAtLeast(0).toFloat()
    track.addView(View(this).apply { background = round(color, 4) }, LinearLayout.LayoutParams(0, dp(8), w))
    track.addView(View(this), LinearLayout.LayoutParams(0, dp(8), r))
    box.addView(track, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(6) })
    box.addView(tv(sub, 11f, SUB).apply { setPadding(0, dp(4), 0, 0) })
    if (onClick != null) box.setOnClickListener { onClick() }
    return box
}
