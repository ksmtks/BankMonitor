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
        val p = Parser.parse(title, if (big.isNotEmpty()) big else text) ?: return

        val app = applicationContext
        val txn = Txn(0, sbn.postTime, p.kind, p.amount, p.party, p.account, p.balance, p.raw)
        if (!Db.get(app).insert(txn)) return

        val pf = Prefs(app)
        if (pf.instant && pf.configured) {
            Thread {
                val dep = txn.isDeposit
                val msg = buildString {
                    append(if (dep) "💰 입금 알림\n" else "💸 출금 알림\n")
                    append(if (dep) "${p.party} → ${p.account}\n" else "${p.account} → ${p.party}\n")
                    append(Fmt.amount(txn))
                    if (p.balance >= 0) append(" · 잔액 ${Fmt.won(p.balance)}")
                    append("\n${Fmt.dt(sbn.postTime)}")
                }
                Telegram.send(pf.token, pf.chatId, msg)
            }.start()
        }
    }
}
