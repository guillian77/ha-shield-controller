package com.dekadmin.hastield.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.dekadmin.hastield.ha.HAEntity
import com.dekadmin.hastield.ha.HomeAssistantApi
import com.dekadmin.hastield.ha.SettingsManager

/**
 * Overlay flottant affichant quelques entités par-dessus l'application au premier
 * plan. Ouvert et fermé par la touche configurée (voir [HaKeyService]).
 */
class OverlayService : Service() {

    companion object {
        private const val ACTION_SHOW = "com.dekadmin.hastield.SHOW_OVERLAY"
        private const val ACTION_HIDE = "com.dekadmin.hastield.HIDE_OVERLAY"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 42
        private const val REFRESH_MS = 3000L

        fun toggle(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = if (instance == null) ACTION_SHOW else ACTION_HIDE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        @Volatile
        private var instance: OverlayService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var settings: SettingsManager
    private lateinit var api: HomeAssistantApi
    private var overlayView: View? = null
    private var rowsContainer: LinearLayout? = null

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsManager(this)
        api = HomeAssistantApi(settings)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        when (intent?.action) {
            ACTION_HIDE -> hide()
            else -> show()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        instance = null
        super.onDestroy()
    }

    private fun show() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(this)) {
            android.widget.Toast.makeText(
                this,
                "Autorise « Affichage par-dessus les autres applications » pour HA Shield Control",
                android.widget.Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return
        }
        val view = buildOverlay()
        overlayView = view
        val params = WindowManager.LayoutParams(
            dp(460),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(24)
            y = dp(24)
        }
        windowManager.addView(view, params)
        view.requestFocus()
        refresh()
        handler.postDelayed(refreshRunnable, REFRESH_MS)
    }

    private fun hide() {
        handler.removeCallbacks(refreshRunnable)
        removeOverlay()
        stopSelf()
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
                // déjà retiré
            }
        }
        overlayView = null
        rowsContainer = null
    }

    private fun buildOverlay(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF21A1C21.toInt(), 0xFF3E8EF7.toInt())
            setPadding(dp(18), dp(14), dp(18), dp(16))
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    hide()
                    true
                } else {
                    false
                }
            }
        }

        root.addView(TextView(this).apply {
            text = "Home Assistant"
            setTextColor(0xFFF3F4F6.toInt())
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "Retour pour fermer"
            setTextColor(0xFF9BA1AA.toInt())
            textSize = 11f
            setPadding(0, dp(2), 0, dp(8))
        })

        rowsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(rowsContainer)

        return root
    }

    private fun refresh() {
        val container = rowsContainer ?: return
        val wanted = settings.effectiveOverlayEntities()
        if (wanted.isEmpty()) {
            container.removeAllViews()
            container.addView(label("Aucune entité sélectionnée pour l'overlay", 0xFF9BA1AA.toInt()))
            return
        }

        api.fetchEntities { fetched ->
            if (fetched == null) {
                container.removeAllViews()
                container.addView(label("Connexion impossible", 0xFFFFC24B.toInt()))
                return@fetchEntities
            }
            container.removeAllViews()
            val entities = fetched.filter { wanted.contains(it.entityId) }
            if (entities.isEmpty()) {
                container.addView(label("Entités indisponibles", 0xFF9BA1AA.toInt()))
            }
            entities.forEach { container.addView(row(it)) }
        }
    }

    private fun row(entity: HAEntity): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = entity.name
            setTextColor(0xFFF3F4F6.toInt())
            textSize = 15f
        })
        texts.addView(TextView(this).apply {
            text = if (entity.type == "light") {
                if (entity.state == "on") "Allumée" else "Éteinte"
            } else {
                val current = entity.attributes["current_temperature"]
                val target = entity.attributes["temperature"]
                "Actuel $current°C · cible $target°C"
            }
            setTextColor(0xFF9BA1AA.toInt())
            textSize = 12f
        })
        row.addView(texts)

        if (entity.type == "light") {
            val isOn = entity.state == "on"
            val toggle = Switch(this).apply {
                isChecked = isOn
                setOnCheckedChangeListener { _, checked ->
                    api.callService(
                        "light",
                        if (checked) "turn_on" else "turn_off",
                        entity.entityId
                    )
                    handler.postDelayed({ refresh() }, 800)
                }
            }
            row.addView(toggle)
        }

        return row
    }

    private fun label(text: String, color: Int): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = 13f
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun rounded(fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(fill)
            setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Overlay Home Assistant",
                NotificationManager.IMPORTANCE_MIN
            )
            channel.setShowBadge(false)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Overlay Home Assistant")
            .setContentText("Affiché par-dessus l'application en cours")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }
}
