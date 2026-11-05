package com.dekadmin.hastield.ha

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SettingsManager(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "ha_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var serverUrl: String
        get() = sharedPreferences.getString("server_url", "http://homeassistant.local:8123")
            ?: "http://homeassistant.local:8123"
        set(value) = sharedPreferences.edit().putString("server_url", value).apply()

    /** Jeton d'accès : long-lived token saisi à la main, ou jeton OAuth rafraîchi. */
    var accessToken: String
        get() = sharedPreferences.getString("access_token", "") ?: ""
        set(value) = sharedPreferences.edit().putString("access_token", value).apply()

    /** Non vide uniquement si la connexion a été faite via la page de login. */
    var refreshToken: String
        get() = sharedPreferences.getString("refresh_token", "") ?: ""
        set(value) = sharedPreferences.edit().putString("refresh_token", value).apply()

    /** client_id renvoyé par le frontend, nécessaire au rafraîchissement OAuth. */
    var oauthClientId: String
        get() = sharedPreferences.getString("oauth_client_id", "") ?: ""
        set(value) = sharedPreferences.edit().putString("oauth_client_id", value).apply()

    /** Horodatage (ms) d'expiration du jeton d'accès, 0 si inconnu. */
    var accessTokenExpiresAt: Long
        get() = sharedPreferences.getLong("access_token_expires_at", 0L)
        set(value) = sharedPreferences.edit().putLong("access_token_expires_at", value).apply()

    val canRefresh: Boolean
        get() = refreshToken.isNotBlank()

    fun clearSession() {
        sharedPreferences.edit()
            .remove("access_token")
            .remove("refresh_token")
            .remove("oauth_client_id")
            .remove("access_token_expires_at")
            .apply()
    }

    fun getSelectedEntities(): Set<String> {
        return sharedPreferences.getStringSet("selected_entities", emptySet()) ?: emptySet()
    }

    fun saveSelectedEntities(entities: Set<String>) {
        sharedPreferences.edit().putStringSet("selected_entities", entities).apply()
    }
}
