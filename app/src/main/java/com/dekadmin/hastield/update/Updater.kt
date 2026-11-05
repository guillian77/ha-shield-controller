package com.dekadmin.hastield.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String
)

/**
 * Vérifie la dernière version publiée dans la release GitHub `latest` et
 * propose l'installation de l'APK depuis l'application.
 */
class Updater(private val context: Context) {

    companion object {
        private const val REPO = "guillian77/ha-shield-controller"
        private const val VERSION_URL =
            "https://github.com/$REPO/releases/latest/download/version.json"
        private const val DEFAULT_APK_URL =
            "https://github.com/$REPO/releases/latest/download/app-debug.apk"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Renvoie la mise à jour disponible, ou null si l'app est à jour. */
    fun check(currentVersionCode: Int, callback: (UpdateInfo?) -> Unit) {
        val request = Request.Builder().url(VERSION_URL).header("Cache-Control", "no-cache").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        callback(null)
                        return
                    }
                    val body = resp.body?.string().orEmpty()
                    val info = try {
                        val json = JSONObject(body)
                        val code = json.optInt("versionCode", 0)
                        if (code <= currentVersionCode) {
                            null
                        } else {
                            UpdateInfo(
                                versionCode = code,
                                versionName = json.optString("versionName", code.toString()),
                                apkUrl = json.optString("apkUrl", DEFAULT_APK_URL)
                            )
                        }
                    } catch (e: Exception) {
                        null
                    }
                    callback(info)
                }
            }
        })
    }

    /**
     * Télécharge l'APK puis ouvre l'installateur système.
     * [onState] reçoit un message d'état lisible par l'utilisateur.
     */
    fun downloadAndInstall(update: UpdateInfo, onState: (String) -> Unit) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "app-${update.versionCode}.apk")
        if (target.exists()) target.delete()

        val request = Request.Builder().url(update.apkUrl).build()
        onState("Téléchargement…")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onState("Échec du téléchargement : ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful || resp.body == null) {
                        onState("Échec du téléchargement (HTTP ${resp.code})")
                        return
                    }
                    try {
                        resp.body!!.byteStream().use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                    } catch (e: Exception) {
                        onState("Échec de l'écriture du fichier : ${e.message}")
                        return
                    }
                }
                try {
                    install(target)
                    onState("Installateur ouvert")
                } catch (e: Exception) {
                    onState("Impossible d'ouvrir l'installateur : ${e.message}")
                }
            }
        })
    }

    private fun install(apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
