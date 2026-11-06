package com.dekadmin.hastield.ha

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Deux stockages distincts, volontairement :
 *
 * - `configPrefs` : données non sensibles (URL du serveur, entités sélectionnées)
 *   dans des SharedPreferences normales. Elles survivent à une mise à jour, à un
 *   changement de clé de signature et à un Keystore réinitialisé.
 * - `securePrefs` : jetons uniquement, chiffrés. Si le Keystore est indisponible
 *   (clé invalidée après une mise à jour système, par exemple), l'app continue de
 *   fonctionner : seule la session est à refaire, la configuration est conservée.
 */
class SettingsManager(context: Context) {

    companion object {
        private const val CONFIG_FILE = "ha_config"
        private const val SECURE_FILE = "ha_secure_prefs"
    }

    private val configPrefs: SharedPreferences =
        context.getSharedPreferences(CONFIG_FILE, Context.MODE_PRIVATE)

    private val securePrefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        null
    }

    init {
        // Reprise des données des versions précédentes, qui stockaient tout dans
        // le fichier chiffré : sans cette migration, la mise à jour ferait
        // réapparaître l'URL par défaut et viderait la sélection d'entités.
        if (!configPrefs.contains("server_url")) {
            securePrefs?.getString("server_url", null)?.let { configPrefs.edit().putString("server_url", it).apply() }
        }
        if (!configPrefs.contains("selected_entities")) {
            securePrefs?.getStringSet("selected_entities", null)?.let {
                configPrefs.edit().putStringSet("selected_entities", it).apply()
            }
        }
    }

    val secureStorageAvailable: Boolean
        get() = securePrefs != null

    var serverUrl: String
        get() = configPrefs.getString("server_url", "http://homeassistant.local:8123")
            ?: "http://homeassistant.local:8123"
        set(value) = configPrefs.edit().putString("server_url", value).apply()

    /** Jeton d'accès : long-lived token saisi à la main, ou jeton OAuth rafraîchi. */
    var accessToken: String
        get() = securePrefs?.getString("access_token", "") ?: ""
        set(value) = securePrefs?.edit()?.putString("access_token", value)?.apply() ?: Unit

    /** Non vide uniquement si la connexion a été faite via la page de login. */
    var refreshToken: String
        get() = securePrefs?.getString("refresh_token", "") ?: ""
        set(value) = securePrefs?.edit()?.putString("refresh_token", value)?.apply() ?: Unit

    /** client_id renvoyé par le frontend, nécessaire au rafraîchissement OAuth. */
    var oauthClientId: String
        get() = securePrefs?.getString("oauth_client_id", "") ?: ""
        set(value) = securePrefs?.edit()?.putString("oauth_client_id", value)?.apply() ?: Unit

    /** Horodatage (ms) d'expiration du jeton d'accès, 0 si inconnu. */
    var accessTokenExpiresAt: Long
        get() = securePrefs?.getLong("access_token_expires_at", 0L) ?: 0L
        set(value) = securePrefs?.edit()?.putLong("access_token_expires_at", value)?.apply() ?: Unit

    val canRefresh: Boolean
        get() = refreshToken.isNotBlank()

    fun clearSession() {
        securePrefs?.edit()
            ?.remove("access_token")
            ?.remove("refresh_token")
            ?.remove("oauth_client_id")
            ?.remove("access_token_expires_at")
            ?.apply()
    }

    fun getSelectedEntities(): Set<String> {
        return configPrefs.getStringSet("selected_entities", emptySet()) ?: emptySet()
    }

    fun saveSelectedEntities(entities: Set<String>) {
        configPrefs.edit().putStringSet("selected_entities", entities).apply()
    }

    /** Ordre d'affichage des entités sur le dashboard (liste d'entity_id). */
    var dashboardOrder: List<String>
        get() = (configPrefs.getString("dashboard_order", "") ?: "")
            .split("\n")
            .filter { it.isNotBlank() }
        set(value) = configPrefs.edit().putString("dashboard_order", value.joinToString("\n")).apply()

    /** Ajoute une entité en fin d'ordre si elle n'y figure pas encore. */
    fun ensureInOrder(entityId: String) {
        val current = dashboardOrder
        if (entityId !in current) dashboardOrder = current + entityId
    }

    /** Déplace une entité de [delta] positions dans l'ordre du dashboard. */
    fun moveInOrder(entityId: String, delta: Int) {
        val current = dashboardOrder.toMutableList()
        val index = current.indexOf(entityId)
        if (index < 0) return
        val target = index + delta
        if (target < 0 || target >= current.size) return
        current.removeAt(index)
        current.add(target, entityId)
        dashboardOrder = current
    }

    /**
     * Sauvegarde complète de la configuration dans un JSON, pour la restaurer
     * après une désinstallation/réinstallation (changement de clé de signature,
     * par exemple). Contient les jetons : à ranger dans un endroit sûr.
     */
    fun exportJson(): String {
        val selected = JSONArray()
        getSelectedEntities().sorted().forEach { selected.put(it) }
        val order = JSONArray()
        dashboardOrder.forEach { order.put(it) }
        return JSONObject().apply {
            put("schema", 1)
            put("serverUrl", serverUrl)
            put("accessToken", accessToken)
            put("refreshToken", refreshToken)
            put("oauthClientId", oauthClientId)
            put("accessTokenExpiresAt", accessTokenExpiresAt)
            put("selectedEntities", selected)
            put("dashboardOrder", order)
        }.toString(2)
    }

    /** Restaure une configuration exportée. Renvoie false si le JSON est invalide. */
    fun importJson(text: String): Boolean {
        return try {
            val json = JSONObject(text)
            if (!json.has("serverUrl") && !json.has("selectedEntities")) return false

            json.optString("serverUrl").takeIf { it.isNotBlank() }?.let { serverUrl = it }
            json.optString("accessToken").takeIf { it.isNotBlank() }?.let { accessToken = it }
            json.optString("refreshToken").takeIf { it.isNotBlank() }?.let { refreshToken = it }
            json.optString("oauthClientId").takeIf { it.isNotBlank() }?.let { oauthClientId = it }
            val expires = json.optLong("accessTokenExpiresAt", 0L)
            if (expires > 0L) accessTokenExpiresAt = expires

            val array = json.optJSONArray("selectedEntities")
            if (array != null) {
                val set = mutableSetOf<String>()
                for (i in 0 until array.length()) {
                    set.add(array.getString(i))
                }
                saveSelectedEntities(set)
            }

            val orderArray = json.optJSONArray("dashboardOrder")
            if (orderArray != null) {
                val order = mutableListOf<String>()
                for (i in 0 until orderArray.length()) {
                    order.add(orderArray.getString(i))
                }
                dashboardOrder = order
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
