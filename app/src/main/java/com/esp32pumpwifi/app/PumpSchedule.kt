package com.esp32pumpwifi.app

/**
 * Représente une ligne de programmation côté application
 * (avant conversion en millisecondes pour l'ESP32)
 */
data class PumpSchedule(

    // Numéro de pompe (1 à 4)
    var pumpNumber: Int,

    // Heure de déclenchement au format "HH:mm"
    var time: String,

    // Quantité à distribuer en millilitres (UI uniquement)
    var quantity: Int,

    // Ligne active ou non
    var enabled: Boolean
)
