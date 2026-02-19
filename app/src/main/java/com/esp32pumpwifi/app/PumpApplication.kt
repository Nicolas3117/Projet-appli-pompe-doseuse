package com.esp32pumpwifi.app

import android.app.Application

class PumpApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 🔔 Channels de notifications Android (API 26+)
        TankNotification.ensureChannels(this)

        // ✅ Ajout minimal : créer aussi le channel "inactivité" au démarrage,
        // pour qu'il apparaisse dans Paramètres > Notifications (même si le worker n'a pas encore notifié).
        ensureInactivityChannel()

        // ✅ Garantit le flush de la queue Telegram dès que le réseau est dispo
        // (utile après reboot / si l'app n'est pas relancée au bon moment)
        TelegramAlertQueue.scheduleFlush(this)

        CriticalAlarmScheduler.ensureScheduled(this)
    }

    private fun ensureInactivityChannel() = InactivityChecker.ensureChannel(this)
}
