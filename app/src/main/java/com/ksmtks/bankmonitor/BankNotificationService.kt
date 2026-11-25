package com.ksmtks.bankmonitor

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class BankNotificationService : NotificationListenerService() {

    override fun onListenerConnected() {
        Scheduler.kick(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!sbn.packageName.contains("kakaobank")) return
        val ex = sbn.notification.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val parsed = Parser.parse(title, if (big.isNotEmpty()) big else text) ?: return

        val app = applicationContext
        val deposit = Deposit(0, sbn.postTime, parsed.amount, parsed.sender, parsed.account, parsed.balance)
        if (!Db.get(app).insert(deposit)) return

        val pf = Prefs(app)
        if (pf.instant && pf.configured) {
            Thread {
                val msg = buildString {
                    append("💰 입금 알림\n")
                    append("${parsed.sender} → ${parsed.account}\n")
                    append("+${Fmt.won(parsed.amount)}")
                    if (parsed.balance >= 0) append(" · 잔액 ${Fmt.won(parsed.balance)}")
                    append("\n${Fmt.dt(sbn.postTime)}")
                }
                Telegram.send(pf.token, pf.chatId, msg)
            }.start()
        }
    }
}
