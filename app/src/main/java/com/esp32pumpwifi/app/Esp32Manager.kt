package com.esp32pumpwifi.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object Esp32Manager {

    private const val PREFS_NAME = "prefs_esp32"
    private const val KEY_MODULES = "esp_modules"

    private val gson = Gson()

    /** Accès aux SharedPreferences */
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // -------------------------------------------------------------------------
    // 🔹 Récupération / Sauvegarde
    // -------------------------------------------------------------------------

    /** 🔹 Récupère tous les modules enregistrés */
    fun getAll(context: Context): MutableList<EspModule> {
        val json = prefs(context).getString(KEY_MODULES, null) ?: return mutableListOf()

        return try {
            val type = object : TypeToken<MutableList<EspModule>>() {}.type
            gson.fromJson<MutableList<EspModule>>(json, type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()   // Sécurité : JSON corrompu
        }
    }

    /** 🔹 Sauvegarde complète de la liste des modules */
    private fun saveAll(context: Context, list: List<EspModule>) {
        prefs(context).edit()
            .putString(KEY_MODULES, gson.toJson(list))
            .apply()
    }

    // -------------------------------------------------------------------------
    // ➕ Ajouter un module
    // -------------------------------------------------------------------------

    fun add(context: Context, module: EspModule) {
        val list = getAll(context)

        // 🔒 Pas de doublon par internalName (identité matérielle)
        if (list.any { it.internalName == module.internalName }) return

        list.add(module)
        saveAll(context, list)
    }

    // -------------------------------------------------------------------------
    // ✏ Mise à jour d’un module existant
    // -------------------------------------------------------------------------

    fun update(context: Context, module: EspModule) {
        val list = getAll(context)
        val index = list.indexOfFirst { it.id == module.id }

        if (index >= 0) {
            list[index] = module
            saveAll(context, list)
        }
    }

    // -------------------------------------------------------------------------
    // 🗑 Suppression d’un module
    // -------------------------------------------------------------------------

    fun delete(context: Context, id: Long) {
        val list = getAll(context)
        list.removeAll { it.id == id }
        saveAll(context, list)
    }

    // -------------------------------------------------------------------------
    // ⭐ Module actif
    // -------------------------------------------------------------------------

    /** Retourne tous les modules actifs */
    fun getActiveModules(context: Context): List<EspModule> =
        getAll(context).filter { it.isActive }

    /** Retourne le module actif (ou null) */
    fun getActive(context: Context): EspModule? =
        getAll(context).firstOrNull { it.isActive }

    // -------------------------------------------------------------------------
    // 🔧 Mise à jour automatique IP (scan réseau)
    // -------------------------------------------------------------------------

    fun updateIp(context: Context, internalName: String, newIp: String) {
        val list = getAll(context)
        val module = list.firstOrNull { it.internalName == internalName } ?: return

        if (module.ip != newIp) {
            module.ip = newIp
            saveAll(context, list)
        }
    }

    // -------------------------------------------------------------------------
    // 🔍 Recherche par nom interne (identité ESP32)
    // -------------------------------------------------------------------------

    fun getByInternalName(context: Context, internalName: String): EspModule? =
        getAll(context).firstOrNull { it.internalName == internalName }

    // -------------------------------------------------------------------------
    // ✅ RECHERCHE PAR ID (UTILISÉ POUR NOTIFS / LOGIQUE INTERNE)
    // -------------------------------------------------------------------------

    fun getById(context: Context, id: Long): EspModule? =
        getAll(context).firstOrNull { it.id == id }
}
