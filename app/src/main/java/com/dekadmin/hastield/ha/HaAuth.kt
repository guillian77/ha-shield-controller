package com.dekadmin.hastield.ha

import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Gère les jetons OAuth récupérés depuis la page de login de Home Assistant.
 *
 * Home Assistant délivre un access_token de 30 minutes et un refresh_token
 * durable : l'application rafraîchit donc le jeton toute seule, l'utilisateur
 * ne saisit plus de token à la main.
 */
class HaAuth(private val settings: SettingsManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Enregistre les jetons trouvés dans le localStorage du frontend
     * (clé `hassTokens`, JSON : access_token, refresh_token, expires, clientId).
     */
    fun acceptFrontendTokens(json: JSONObject): Boolean {
        val access = json.optString("access_token")
        if (access.isBlank()) return false

        settings.accessToken = access
        json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { settings.refreshToken = it }
        json.optString("clientId").takeIf { it.isNotBlank() }?.let { settings.oauthClientId = it }

        val expiresIn = json.optLong("expires_in", 1800L)
        val expires = if (json.has("expires")) {
            json.optLong("expires")
        } else {
            System.currentTimeMillis() + expiresIn * 1000L
        }
        settings.accessTokenExpiresAt = expires
        return true
    }

    /** Le jeton est-il absent ou proche de l'expiration ? */
    fun needsRefresh(): Boolean {
        if (settings.accessToken.isBlank()) return true
        val expiresAt = settings.accessTokenExpiresAt
        if (expiresAt <= 0L) return false // long-lived token : pas d'expiration connue
        return System.currentTimeMillis() > expiresAt - 60_000L
    }

    fun refresh(callback: (Boolean) -> Unit) {
        val base = settings.serverUrl.trimEnd('/')
        val refreshToken = settings.refreshToken
        if (base.isBlank() || refreshToken.isBlank()) {
            callback(false)
            return
        }

        val builder = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
        if (settings.oauthClientId.isNotBlank()) {
            builder.add("client_id", settings.oauthClientId)
        }

        val request = Request.Builder()
            .url("$base/auth/token")
            .post(builder.build())
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        callback(false)
                        return
                    }
                    val body = resp.body?.string().orEmpty()
                    try {
                        val json = JSONObject(body)
                        val access = json.optString("access_token")
                        if (access.isBlank()) {
                            callback(false)
                            return
                        }
                        settings.accessToken = access
                        json.optString("refresh_token").takeIf { it.isNotBlank() }?.let {
                            settings.refreshToken = it
                        }
                        val expiresIn = json.optLong("expires_in", 1800L)
                        settings.accessTokenExpiresAt = System.currentTimeMillis() + expiresIn * 1000L
                        callback(true)
                    } catch (e: Exception) {
                        callback(false)
                    }
                }
            }
        })
    }

    /** Rafraîchit si nécessaire puis exécute [action] avec un jeton valide. */
    fun ensureValidToken(callback: (Boolean) -> Unit) {
        if (!needsRefresh()) {
            callback(true)
            return
        }
        if (!settings.canRefresh) {
            callback(settings.accessToken.isNotBlank())
            return
        }
        refresh(callback)
    }
}
