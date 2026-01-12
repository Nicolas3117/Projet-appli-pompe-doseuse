package com.esp32pumpwifi.app

/**
 * internalName → identifiant unique provenant du module ESP32 (ex : "pompe-14C9")
 * displayName  → nom utilisateur (ex : "Pompe Calcium")
 *
 * IMPORTANT :
 * - internalName NE DOIT JAMAIS être modifié après création (sert d'identifiant matériel)
 * - displayName peut être modifié par l'utilisateur librement
 */
data class EspModule(

    // ID interne pour stockage local (généré automatiquement)
    val id: Long = System.currentTimeMillis(),

    // Nom affiché dans l’application
    var displayName: String = "Nouveau module",

    // Identifiant matériel permanent (renvoyé par l’ESP32 → via /id)
    val internalName: String,

    // Adresse IP actuelle du module (STA ou AP)
    var ip: String,

    // Module actuellement sélectionné dans l'application
    var isActive: Boolean = false
)
