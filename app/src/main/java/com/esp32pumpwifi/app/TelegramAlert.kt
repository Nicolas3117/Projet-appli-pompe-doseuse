package com.esp32pumpwifi.app

data class TelegramAlert(
    val id: String,
    val espId: Long,
    val pumpNum: Int,
    val type: String,
    val message: String,
    val timestamp: Long
) {
    companion object {
        private const val TYPE_LOW_LEVEL = "LOW_LEVEL"
        private const val TYPE_EMPTY_TANK = "EMPTY_TANK"

        fun lowLevel(
            espId: Long,
            pumpNum: Int,
            percent: Int,
            fullPumpName: String,
            timestamp: Long = System.currentTimeMillis()
        ): TelegramAlert {
            val type = TYPE_LOW_LEVEL
            return TelegramAlert(
                id = buildId(type, espId, pumpNum, timestamp),
                espId = espId,
                pumpNum = pumpNum,
                type = type,
                message = "⚠️ NIVEAU BAS\n$fullPumpName\n$percent % restant",
                timestamp = timestamp
            )
        }

        fun emptyTank(
            espId: Long,
            pumpNum: Int,
            fullPumpName: String,
            timestamp: Long = System.currentTimeMillis()
        ): TelegramAlert {
            val type = TYPE_EMPTY_TANK
            return TelegramAlert(
                id = buildId(type, espId, pumpNum, timestamp),
                espId = espId,
                pumpNum = pumpNum,
                type = type,
                message = "🚨 RÉSERVOIR VIDE\n$fullPumpName\nDistribution impossible",
                timestamp = timestamp
            )
        }

        private fun buildId(
            type: String,
            espId: Long,
            pumpNum: Int,
            timestamp: Long
        ): String = "$type:$espId:$pumpNum:$timestamp"
    }
}
