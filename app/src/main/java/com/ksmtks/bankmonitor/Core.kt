package com.ksmtks.bankmonitor

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 거래 1건. kind: "D"=입금, "W"=출금. party=입금이면 보낸 사람, 출금이면 받는 사람. */
data class Txn(
    val id: Long,
    val ts: Long,
    val kind: String,
    val amount: Long,
    val party: String,
    val account: String,
    val balance: Long, // -1 = unknown
    val raw: String
) {
    val isDeposit: Boolean get() = kind == "D"
}

class Totals(list: List<Txn>) {
    val deposits: List<Txn> = list.filter { it.isDeposit }
    val withdrawals: List<Txn> = list.filter { !it.isDeposit }
    val dSum: Long = deposits.sumOf { it.amount }
    val wSum: Long = withdrawals.sumOf { it.amount }
    val net: Long get() = dSum - wSum
}

data class Parsed(
    val kind: String, val amount: Long, val party: String,
    val account: String, val balance: Long, val raw: String
)

/** 카카오뱅크 입출금 알림 파서.
 *  예) 제목 "입금 1원" / 본문 "김산 → 입출금통장(9540) 잔액 692,801원" */
object Parser {
    private val amountRe = Regex("""(입금|출금)\s*([\d,]+)\s*원""")
    private val balanceRe = Regex("""잔액\s*([\d,]+)\s*원""")
    private val acctRe = Regex("""\(\d+\)""")

    private fun num(s: String): Long = s.replace(",", "").toLongOrNull() ?: 0L

    fun parse(title: String, text: String): Parsed? {
        val all = "$title\n$text"
        val m = amountRe.find(title) ?: amountRe.find(all) ?: return null
        val kind = if (m.groupValues[1] == "입금") "D" else "W"
        val amount = num(m.groupValues[2])
        if (amount <= 0) return null
        val balance = balanceRe.find(all)?.let { num(it.groupValues[1]) } ?: -1L

        val clean = balanceRe.replace(text, "").trim()
        var party = ""
        var account = ""
        val arrow = clean.indexOf('→')
        if (arrow >= 0) {
            val left = clean.substring(0, arrow).lines().lastOrNull().orEmpty().trim()
            val right = clean.substring(arrow + 1).lines().firstOrNull().orEmpty().trim()
            val leftAcct = acctRe.containsMatchIn(left)
            val rightAcct = acctRe.containsMatchIn(right)
            if (leftAcct && !rightAcct) { party = right; account = left } else { party = left; account = right }
        } else {
            party = clean.lines().firstOrNull { it.isNotBlank() && !amountRe.containsMatchIn(it) }?.trim().orEmpty()
        }
        if (party.isEmpty()) party = "알 수 없음"
        return Parsed(kind, amount, party, account, balance, all.trim())
    }
}

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "bank.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE deposits(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, " +
                "amount INTEGER NOT NULL, sender TEXT NOT NULL, account TEXT NOT NULL, balance INTEGER NOT NULL, " +
                "kind TEXT NOT NULL DEFAULT 'D', raw TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL("CREATE INDEX idx_ts ON deposits(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE deposits ADD COLUMN kind TEXT NOT NULL DEFAULT 'D'")
            db.execSQL("ALTER TABLE deposits ADD COLUMN raw TEXT NOT NULL DEFAULT ''")
        }
    }

    /** 같은 알림이 갱신되어 다시 올 때를 대비해 (종류, 금액, 상대, 잔액)이 같고 2분 이내면 중복으로 본다. */
    fun insert(t: Txn): Boolean {
        val db = writableDatabase
        db.rawQuery(
            "SELECT 1 FROM deposits WHERE kind=? AND amount=? AND sender=? AND balance=? AND ts BETWEEN ? AND ? LIMIT 1",
            arrayOf(
                t.kind, t.amount.toString(), t.party, t.balance.toString(),
                (t.ts - 120_000).toString(), (t.ts + 120_000).toString()
            )
        ).use { if (it.moveToFirst()) return false }
        val cv = ContentValues().apply {
            put("ts", t.ts); put("kind", t.kind); put("amount", t.amount); put("sender", t.party)
            put("account", t.account); put("balance", t.balance); put("raw", t.raw)
        }
        return db.insert("deposits", null, cv) != -1L
    }

    private val cols = "id,ts,kind,amount,sender,account,balance,raw"

    private fun android.database.Cursor.toTxn() = Txn(
        getLong(0), getLong(1), getString(2), getLong(3), getString(4), getString(5), getLong(6), getString(7)
    )

    fun between(from: Long, to: Long, limit: Int = -1): List<Txn> {
        val out = ArrayList<Txn>()
        val sql = "SELECT $cols FROM deposits WHERE ts>=? AND ts<? ORDER BY ts DESC" +
            if (limit > 0) " LIMIT $limit" else ""
        readableDatabase.rawQuery(sql, arrayOf(from.toString(), to.toString())).use { c ->
            while (c.moveToNext()) out.add(c.toTxn())
        }
        return out
    }

    fun byId(id: Long): Txn? =
        readableDatabase.rawQuery("SELECT $cols FROM deposits WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.toTxn() else null
        }

    fun delete(id: Long) {
        writableDatabase.delete("deposits", "id=?", arrayOf(id.toString()))
    }

    companion object {
        @Volatile
        private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(ctx.applicationContext).also { inst = it }
        }
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    private fun s(k: String) = sp.getString(k, "") ?: ""
    private fun b(k: String, def: Boolean) = sp.getBoolean(k, def)

    var token: String
        get() = s("token")
        set(v) = sp.edit().putString("token", v.trim()).apply()
    var chatId: String
        get() = s("chatId")
        set(v) = sp.edit().putString("chatId", v.trim()).apply()
    var instant: Boolean
        get() = b("instant", true)
        set(v) = sp.edit().putBoolean("instant", v).apply()
    var daily: Boolean
        get() = b("daily", true)
        set(v) = sp.edit().putBoolean("daily", v).apply()
    var weekly: Boolean
        get() = b("weekly", true)
        set(v) = sp.edit().putBoolean("weekly", v).apply()
    var monthly: Boolean
        get() = b("monthly", true)
        set(v) = sp.edit().putBoolean("monthly", v).apply()

    // 발송 시각: 하루 중 분 단위 (기본 23:55). 요일은 DayOfWeek 값(월=1..일=7), 월간 일자 0=말일
    var dailyMin: Int
        get() = sp.getInt("dailyMin", 23 * 60 + 55)
        set(v) = sp.edit().putInt("dailyMin", v).apply()
    var weeklyMin: Int
        get() = sp.getInt("weeklyMin", 23 * 60 + 55)
        set(v) = sp.edit().putInt("weeklyMin", v).apply()
    var weeklyDow: Int
        get() = sp.getInt("weeklyDow", 7)
        set(v) = sp.edit().putInt("weeklyDow", v).apply()
    var monthlyMin: Int
        get() = sp.getInt("monthlyMin", 23 * 60 + 55)
        set(v) = sp.edit().putInt("monthlyMin", v).apply()
    var monthlyDay: Int
        get() = sp.getInt("monthlyDay", 0)
        set(v) = sp.edit().putInt("monthlyDay", v).apply()
    var schedVer: Int
        get() = sp.getInt("schedVer", 0)
        set(v) = sp.edit().putInt("schedVer", v).apply()

    var lastDaily: String
        get() = s("lastDaily")
        set(v) = sp.edit().putString("lastDaily", v).apply()
    var lastWeekly: String
        get() = s("lastWeekly")
        set(v) = sp.edit().putString("lastWeekly", v).apply()
    var lastMonthly: String
        get() = s("lastMonthly")
        set(v) = sp.edit().putString("lastMonthly", v).apply()

    val configured: Boolean get() = token.isNotBlank() && chatId.isNotBlank()
}

object Fmt {
    private val nf = NumberFormat.getNumberInstance(Locale.KOREA)
    private val dtf = DateTimeFormatter.ofPattern("M/d(E) HH:mm", Locale.KOREAN)
    private val fullf = DateTimeFormatter.ofPattern("yyyy-MM-dd(E) HH:mm:ss", Locale.KOREAN)
    private val df = DateTimeFormatter.ofPattern("yyyy-MM-dd(E)", Locale.KOREAN)
    private val dayf = DateTimeFormatter.ofPattern("M/d(E)", Locale.KOREAN)
    private val timef = DateTimeFormatter.ofPattern("HH:mm", Locale.KOREAN)
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun won(n: Long): String = nf.format(n) + "원"
    fun signed(n: Long): String = if (n >= 0) "+${won(n)}" else "-${won(-n)}"
    fun amount(t: Txn): String = (if (t.isDeposit) "+" else "-") + won(t.amount)
    fun dt(ts: Long): String = dtf.format(Instant.ofEpochMilli(ts).atZone(zone))
    fun full(ts: Long): String = fullf.format(Instant.ofEpochMilli(ts).atZone(zone))
    fun time(ts: Long): String = timef.format(Instant.ofEpochMilli(ts).atZone(zone))
    fun date(d: LocalDate): String = df.format(d)
    fun day(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
    fun dayShort(d: LocalDate): String = dayf.format(d)
    fun millis(d: LocalDate): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()
}

object Telegram {
    /** @return (성공 여부, 메시지) */
    fun send(token: String, chatId: String, text: String): Pair<Boolean, String> {
        if (token.isBlank() || chatId.isBlank()) return false to "텔레그램 토큰/Chat ID가 설정되지 않았습니다"
        for (chunk in text.chunked(3800)) {
            try {
                val conn = URL("https://api.telegram.org/bot${token.trim()}/sendMessage")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.doOutput = true
                val body = "chat_id=${URLEncoder.encode(chatId.trim(), "UTF-8")}" +
                    "&text=${URLEncoder.encode(chunk, "UTF-8")}"
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
                conn.disconnect()
                if (code !in 200..299) return false to "HTTP $code ${resp.take(200)}"
            } catch (e: Exception) {
                return false to (e.message ?: e.javaClass.simpleName)
            }
        }
        return true to "OK"
    }
}
