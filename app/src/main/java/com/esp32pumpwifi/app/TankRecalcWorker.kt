package com.esp32pumpwifi.app

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Worker périodique (toutes les 15 minutes)
 *
 * Rôle :
 * - recalculer les dosages manqués
 * - décrémenter les réservoirs
 * - déclencher les alertes associées
 *
 * ℹ️ ARCHITECTURE :
 * - TankManager : données uniquement (aucune notification)
 * - TankScheduleHelper : logique + décrément
 * - TankAlertManager : notifications niveau bas / réservoir vide
 *
 * ❌ AUCUNE alerte "autonomie faible" ici
 * ❌ AUCUNE notification directe dans ce Worker
 */
class TankRecalcWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {

        val appContext = applicationContext

        val modules =
            Esp32Manager.getAll(appContext)

        if (modules.isEmpty()) {
            Log.i(
                "TANK_RECALC",
                "Aucun module ESP32 configuré"
            )
            return Result.success()
        }

        for (module in modules) {

            Log.i(
                "TANK_RECALC",
                "Recalcul dosages → ${module.displayName}"
            )

            // -------------------------------------------------------------
            // 🔄 RECALCUL GLOBAL
            //
            // Cette méthode :
            // - décrémente les volumes
            // - déclenche les alertes niveau bas / vide
            // - gère les verrous anti-spam
            // -------------------------------------------------------------
            TankScheduleHelper.recalculateFromLastTime(
                context = appContext,
                espId = module.id
            )
        }

        return Result.success()
    }
}
