package com.dekadmin.hastield.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String = ""
)

/** Résultat d'une vérification de mise à jour. */
sealed class UpdateResult {
    data class Available(val info: UpdateInfo) : UpdateResult()
    object UpToDate : UpdateResult()
    data class Failed(val reason: String) : UpdateResult()
}

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
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Renvoie le résultat de la vérification : disponible, à jour, ou l'erreur. */
    fun check(currentVersionCode: Int, callback: (UpdateResult) -> Unit) {
        val request = Request.Builder().url(VERSION_URL).header("Cache-Control", "no-cache").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    callback(UpdateResult.Failed("Réseau indisponible : ${e.message ?: e.javaClass.simpleName}"))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                var result: UpdateResult = UpdateResult.Failed("Réponse inattendue")
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        result = UpdateResult.Failed("HTTP ${resp.code} sur version.json")
                    } else {
                        val body = resp.body?.string().orEmpty()
                        result = try {
                            val json = JSONObject(body)
                            val code = json.optInt("versionCode", 0)
                            if (code <= 0) {
                                UpdateResult.Failed("version.json illisible")
                            } else if (code <= currentVersionCode) {
                                UpdateResult.UpToDate
                            } else {
                                UpdateResult.Available(
                                    UpdateInfo(
                                        versionCode = code,
                                        versionName = json.optString("versionName", code.toString()),
                                        apkUrl = json.optString("apkUrl", DEFAULT_APK_URL),
                                        sha256 = json.optString("sha256", "")
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            UpdateResult.Failed("version.json invalide : ${e.message}")
                        }
                    }
                }
                val finalResult = result
                mainHandler.post { callback(finalResult) }
            }
        })
    }

    /**
     * Télécharge l'APK, vérifie son empreinte puis ouvre l'installateur système.
     * [onState] reçoit un message d'état lisible par l'utilisateur.
     */
    fun downloadAndInstall(update: UpdateInfo, onState: (String) -> Unit) {
        val state: (String) -> Unit = { message -> mainHandler.post { onState(message) } }
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "app-${update.versionCode}.apk")
        if (target.exists()) target.delete()

        val request = Request.Builder().url(update.apkUrl).build()
        state("Téléchargement…")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                state("Échec du téléchargement : ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful || resp.body == null) {
                        state("Échec du téléchargement (HTTP ${resp.code})")
                        return
                    }
                    try {
                        resp.body!!.byteStream().use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                    } catch (e: Exception) {
                        state("Échec de l'écriture du fichier : ${e.message}")
                        return
                    }
                }

                if (update.sha256.isNotBlank()) {
                    val actual = sha256Of(target)
                    if (!actual.equals(update.sha256, ignoreCase = true)) {
                        target.delete()
                        state("Fichier téléchargé corrompu (empreinte différente), réessaie.")
                        return
                    }
                }

                try {
                    install(target)
                    state("Installateur ouvert")
                } catch (e: Exception) {
                    state("Impossible d'ouvrir l'installateur : ${e.message}")
                }
            }
        })
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
