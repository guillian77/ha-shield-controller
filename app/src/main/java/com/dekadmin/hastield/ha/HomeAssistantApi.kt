
package com.dekadmin.hastield.ha

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data.class HAEntity(
    val entityId: String,
    val name: String,
    val state: String,
    val attributes: Map<String, Any>,
    val type: String // "light" or "thermostat"
)

class HomeAssistantApi(private val settings: SettingsManager) {
    private val client = OkHttpClient()

    fun fetchEntities(callback: (List<HAEntity>) -> Unit) {
        val url = "${settings.serverUrl.trimEnd('/')}/api/states"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${settings.accessToken}")
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(emptyList())
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    callback(emptyList())
                    return
                }
                val body = response.body?.string() ?: "[]"
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
                    callback(emptyList())
                }
            }
        })
    }

    fun callService(domain: String, service: String, entityId: String, extraData: Map<String, Any> = emptyMap()) {
        val url = "${settings.serverUrl.trimEnd('/')}/api/services/$domain/$service"
        val json = JSONObject().apply {
            put("entity_id", entityId)
            for ((k, v) in extraData) {
                put(k, v)
            }
        }
        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${settings.accessToken}")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }
}
