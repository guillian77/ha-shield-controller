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

    var accessToken: String
        get() = sharedPreferences.getString("access_token", "") ?: ""
        set(value) = sharedPreferences.edit().putString("access_token", value).apply()

    fun getSelectedEntities(): Set<String> {
        return sharedPreferences.getStringSet("selected_entities", emptySet()) ?: emptySet()
    }

    fun saveSelectedEntities(entities: Set<String>) {
        sharedPreferences.edit().putStringSet("selected_entities", entities).apply()
    }
}
