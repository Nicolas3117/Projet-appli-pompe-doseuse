package com.esp32pumpwifi.app.model

data class Schedule(
    val pumpNumber: Int,     // Numéro de pompe (1..4)
    val time: String,        // Heure programmée (ex: "12:30")
    val volume: Float        // Volume en mL
)
