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

data class Deposit(
    val id: Long,
    val ts: Long,
    val amount: Long,
    val sender: String,
    val account: String,
    val balance: Long // -1 = unknown
)

data class Parsed(val amount: Long, val sender: String, val account: String, val balance: Long)

/** 카카오뱅크 입금 알림 파서.
 *  예) 제목 "입금 1원" / 본문 "김산 → 입출금통장(9540) 잔액 692,801원" */
object Parser {
    private val amountRe = Regex("""입금\s*([\d,]+)\s*원""")
    private val routeRe = Regex("""([^\n→]+?)\s*→\s*([^\n(]+?)\s*\((\d+)\)""")
    private val balanceRe = Regex("""잔액\s*([\d,]+)\s*원""")

    private fun num(s: String): Long = s.replace(",", "").toLongOrNull() ?: 0L

    fun parse(title: String, text: String): Parsed? {
        val all = "$title\n$text"
        val am = amountRe.find(all) ?: return null
        val amount = num(am.groupValues[1])
        if (amount <= 0) return null
        val route = routeRe.find(text) ?: routeRe.find(all)
        val sender = route?.groupValues?.get(1)?.trim().orEmpty().ifEmpty { "알 수 없음" }
        val account = if (route != null) "${route.groupValues[2].trim()}(${route.groupValues[3]})" else ""
        val balance = balanceRe.find(all)?.let { num(it.groupValues[1]) } ?: -1L
        return Parsed(amount, sender, account, balance)
    }
}

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "bank.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE deposits(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, " +
                "amount INTEGER NOT NULL, sender TEXT NOT NULL, account TEXT NOT NULL, balance INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_ts ON deposits(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** 같은 알림이 갱신되어 다시 올 때를 대비해 (금액, 보낸사람, 잔액)이 같고 2분 이내면 중복으로 본다. */
    fun insert(d: Deposit): Boolean {
        val db = writableDatabase
        db.rawQuery(
            "SELECT 1 FROM deposits WHERE amount=? AND sender=? AND balance=? AND ts BETWEEN ? AND ? LIMIT 1",
            arrayOf(
                d.amount.toString(), d.sender, d.balance.toString(),
                (d.ts - 120_000).toString(), (d.ts + 120_000).toString()
            )
        ).use { if (it.moveToFirst()) return false }
        val cv = ContentValues().apply {
            put("ts", d.ts); put("amount", d.amount); put("sender", d.sender)
            put("account", d.account); put("balance", d.balance)
        }
        return db.insert("deposits", null, cv) != -1L
    }

    fun between(from: Long, to: Long, limit: Int = -1): List<Deposit> {
        val out = ArrayList<Deposit>()
        val sql = "SELECT id,ts,amount,sender,account,balance FROM deposits WHERE ts>=? AND ts<? ORDER BY ts DESC" +
            if (limit > 0) " LIMIT $limit" else ""
        readableDatabase.rawQuery(sql, arrayOf(from.toString(), to.toString())).use { c ->
            while (c.moveToNext()) {
                out.add(Deposit(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4), c.getLong(5)))
            }
        }
        return out
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
    // 발송 시각: 하루 중 분 단위 (기본 00:10). 요일은 java.time.DayOfWeek 값(월=1..일=7)
    var dailyMin: Int
        get() = sp.getInt("dailyMin", 10)
        set(v) = sp.edit().putInt("dailyMin", v).apply()
    var weeklyMin: Int
        get() = sp.getInt("weeklyMin", 10)
        set(v) = sp.edit().putInt("weeklyMin", v).apply()
    var weeklyDow: Int
        get() = sp.getInt("weeklyDow", 1)
        set(v) = sp.edit().putInt("weeklyDow", v).apply()
    var monthlyMin: Int
        get() = sp.getInt("monthlyMin", 10)
        set(v) = sp.edit().putInt("monthlyMin", v).apply()
    var monthlyDay: Int
        get() = sp.getInt("monthlyDay", 1)
        set(v) = sp.edit().putInt("monthlyDay", v).apply()
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
    private val df = DateTimeFormatter.ofPattern("yyyy-MM-dd(E)", Locale.KOREAN)
    private val dayf = DateTimeFormatter.ofPattern("M/d(E)", Locale.KOREAN)
    private val timef = DateTimeFormatter.ofPattern("HH:mm", Locale.KOREAN)
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun won(n: Long): String = nf.format(n) + "원"
    fun dt(ts: Long): String = dtf.format(Instant.ofEpochMilli(ts).atZone(zone))
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
