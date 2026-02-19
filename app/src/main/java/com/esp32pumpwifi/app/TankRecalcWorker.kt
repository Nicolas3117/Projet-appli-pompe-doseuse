package com.esp32pumpwifi.app

import android.content.Context
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
        TankChecker.run(applicationContext)

        return Result.success()
    }
}
