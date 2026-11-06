package com.dekadmin.hastield.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.dekadmin.hastield.ha.SettingsManager
import com.dekadmin.hastield.service.HaKeyService

/**
 * Activité plein écran qui capture la touche de la télécommande et le type
 * d'appui (simple, double, long) à utiliser pour l'overlay.
 *
 * L'utilisateur appuie réellement sur la touche : les événements arrivent à
 * l'activité qui est au premier plan, aucun accès privilégié n'est nécessaire
 * pour cette étape.
 */
class KeyCaptureActivity : Activity() {

    companion object {
        private const val LONG_PRESS_MS = 600L
        private const val DOUBLE_PRESS_MS = 400L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var downAt = 0L
    private var pressedCode = 0
    private var lastCompletedAt = 0L
    private var lastCompletedCode = 0
    private var pendingSingle: Runnable? = null
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    private fun buildUi(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0D0E11"))
            setPadding(64, 64, 64, 64)
        }

        root.addView(TextView(this).apply {
            text = "Configure la touche de l'overlay"
            setTextColor(Color.parseColor("#F3F4F6"))
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "Appuie sur la touche souhaitée.\nUn appui = simple, deux appuis rapides = double, maintenir = appui long."
            setTextColor(Color.parseColor("#9BA1AA"))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        })

        root.addView(TextView(this).apply {
            text = "Retour pour annuler"
            setTextColor(Color.parseColor("#3E8EF7"))
            textSize = 14f
            gravity = Gravity.CENTER
        })

        return root
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        if (event.repeatCount == 0) {
            downAt = SystemClock.elapsedRealtime()
            pressedCode = keyCode
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (done || keyCode == KeyEvent.KEYCODE_BACK) return true

        val now = SystemClock.elapsedRealtime()
        val duration = now - downAt

        if (duration >= LONG_PRESS_MS) {
            complete(keyCode, HaKeyService.GESTURE_LONG)
            return true
        }

        if (lastCompletedCode == keyCode && now - lastCompletedAt < DOUBLE_PRESS_MS) {
            pendingSingle?.let { handler.removeCallbacks(it) }
            pendingSingle = null
            complete(keyCode, HaKeyService.GESTURE_DOUBLE)
            return true
        }

        lastCompletedCode = keyCode
        lastCompletedAt = now
        val runnable = Runnable { complete(keyCode, HaKeyService.GESTURE_SINGLE) }
        pendingSingle = runnable
        handler.postDelayed(runnable, DOUBLE_PRESS_MS)
        return true
    }

    private fun complete(keyCode: Int, gesture: String) {
        if (done) return
        done = true

        val settings = SettingsManager(this)
        settings.overlayKeyCode = keyCode
        settings.overlayGesture = gesture

        val label = when (gesture) {
            HaKeyService.GESTURE_DOUBLE -> "double appui"
            HaKeyService.GESTURE_LONG -> "appui long"
            else -> "appui simple"
        }
        Toast.makeText(
            this,
            "Touche ${KeyEvent.keyCodeToString(keyCode)} · $label",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
