package com.dekadmin.hastield.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.dekadmin.hastield.ha.SettingsManager

/**
 * Cappe la touche configurée au-dessus de toutes les applications et bascule
 * l'overlay. Nécessite que le service soit activé dans les réglages
 * d'accessibilité Android (impossible autrement de voir les touches d'une autre
 * application sur Android TV).
 */
class HaKeyService : AccessibilityService() {

    companion object {
        const val GESTURE_SINGLE = "single"
        const val GESTURE_DOUBLE = "double"
        const val GESTURE_LONG = "long"

        // Touche maintenue enfoncée au-delà de ce délai = appui long.
        private const val LONG_PRESS_MS = 600L
        // Deux appuis séparés de moins de ce délai = double appui.
        private const val DOUBLE_PRESS_MS = 350L

        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Vrai pendant la capture d'une touche : le service doit alors laisser
         * passer les événements, sinon il consomme la touche déjà configurée et
         * l'activité de capture ne reçoit plus rien.
         */
        @Volatile
        var captureActive: Boolean = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private var settings: SettingsManager? = null

    private var lastPressAt = 0L
    private var lastPressCode = 0
    private var longPressHandled = false

    private val longPressRunnable = Runnable {
        val s = settings ?: return@Runnable
        if (s.overlayGesture == GESTURE_LONG) {
            longPressHandled = true
            toggleOverlay()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = SettingsManager(this)
        isRunning = true
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Pendant la capture d'une touche, ne rien intercepter : l'activité de
        // capture doit recevoir tous les appuis, y compris celui déjà configuré.
        if (captureActive) return false

        val s = settings ?: return false
        val configured = s.overlayKeyCode
        if (configured == 0 || event.keyCode != configured) return false

        when (s.overlayGesture) {
            GESTURE_LONG -> return handleLong(event)
            GESTURE_DOUBLE -> return handleDouble(event)
            else -> return handleSingle(event)
        }
    }

    private fun handleLong(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    longPressHandled = false
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                }
            }
            KeyEvent.ACTION_UP -> handler.removeCallbacks(longPressRunnable)
        }
        // On consomme l'événement pour ne pas le laisser à l'application au premier plan.
        return true
    }

    private fun handleSingle(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
            toggleOverlay()
        }
        return true
    }

    private fun handleDouble(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false

        val now = System.currentTimeMillis()
        if (now - lastPressAt < DOUBLE_PRESS_MS && lastPressCode == event.keyCode) {
            lastPressAt = 0L
            toggleOverlay()
        } else {
            lastPressAt = now
            lastPressCode = event.keyCode
        }
        return true
    }

    private fun toggleOverlay() {
        OverlayService.toggle(this)
    }
}
