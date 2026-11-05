package com.dekadmin.hastield.ha

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class HAEntity(
    val entityId: String,
    val name: String,
    val state: String,
    val attributes: Map<String, Any>,
    val type: String // "light" or "thermostat"
)

class HomeAssistantApi(private val settings: SettingsManager) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val auth = HaAuth(settings)

    /**
     * Exécute une requête. En cas de 401 et si un refresh_token est disponible,
     * le jeton est rafraîchi puis la requête rejouée une fois.
     */
    private fun execute(
        retried: Boolean = false,
        buildRequest: () -> Request,
        callback: (Response?) -> Unit
    ) {
        client.newCall(buildRequest()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.code == 401 && !retried && settings.canRefresh) {
                    response.close()
                    auth.refresh { ok ->
                        if (ok) execute(true, buildRequest, callback) else callback(null)
                    }
                    return
                }
                callback(response)
            }
        })
    }

    private fun authorized(builder: Request.Builder): Request.Builder = builder
        .addHeader("Authorization", "Bearer ${settings.accessToken}")
        .addHeader("Content-Type", "application/json")

    fun fetchEntities(callback: (List<HAEntity>?) -> Unit) {
        execute(
            buildRequest = {
                authorized(Request.Builder().url("${settings.serverUrl.trimEnd('/')}/api/states")).build()
            }
        ) { response ->
            if (response == null) {
                callback(null)
                return@execute
            }
            response.use { resp ->
                if (!resp.isSuccessful) {
                    callback(null)
                    return@use
                }
                val body = resp.body?.string() ?: "[]"
                try {
                    val jsonArray = JSONArray(body)
                    val list = mutableListOf<HAEntity>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val entityId = obj.getString("entity_id")
                        if (entityId.startsWith("light.") || entityId.startsWith("climate.")) {
                            val state = obj.getString("state")
                            val attrObj = obj.getJSONObject("attributes")
                            val name = attrObj.optString("friendly_name", entityId)
                            val type = if (entityId.startsWith("light.")) "light" else "thermostat"

                            val attrs = mutableMapOf<String, Any>()
                            val keys = attrObj.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                attrs[k] = attrObj.get(k)
                            }

                            list.add(HAEntity(entityId, name, state, attrs, type))
                        }
                    }
                    callback(list)
                } catch (e: Exception) {
                    callback(null)
                }
            }
        }
    }

    fun callService(
        domain: String,
        service: String,
        entityId: String,
        extraData: Map<String, Any> = emptyMap()
    ) {
        val json = JSONObject().apply {
            put("entity_id", entityId)
            for ((k, v) in extraData) {
                put(k, v)
            }
        }
        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        execute(
            buildRequest = {
                authorized(
                    Request.Builder()
                        .url("${settings.serverUrl.trimEnd('/')}/api/services/$domain/$service")
                        .post(body)
                ).build()
            }
        ) { response -> response?.close() }
    }
}
