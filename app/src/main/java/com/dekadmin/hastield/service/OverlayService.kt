package com.dekadmin.hastield.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.dekadmin.hastield.R
import com.dekadmin.hastield.ha.HAEntity
import com.dekadmin.hastield.ha.HomeAssistantApi
import com.dekadmin.hastield.ha.SettingsManager

/**
 * Overlay flottant affichant quelques entités par-dessus l'application au premier
 * plan. Ouvert et fermé par la touche configurée (voir [HaKeyService]).
 *
 * Les vues sont créées avec le thème sombre de l'application : un service
 * n'hérite d'aucun thème et produirait sinon des widgets clairs (switch gris,
 * texte noir) sur un panneau sombre.
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

        /** Ferme l'overlay s'il est affiché (utilisé avant la capture d'une touche). */
        fun hideIfShowing(context: Context) {
            if (instance == null) return
            val intent = Intent(context, OverlayService::class.java).apply { action = ACTION_HIDE }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Vrai tant que le panneau est affiché. */
        @Volatile
        var isShowing: Boolean = false
            private set

        /**
         * Les fenêtres d'overlay ne reçoivent pas toujours le focus clavier sur
         * Android TV : les touches de navigation sont donc transmises par le
         * service d'accessibilité, qui les voit de toute façon.
         */
        fun handleKey(keyCode: Int): Boolean {
            val service = instance ?: return false
            service.onExternalKey(keyCode)
            return true
        }

        @Volatile
        private var instance: OverlayService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var settings: SettingsManager
    private lateinit var api: HomeAssistantApi
    private lateinit var ui: Context

    private var overlayView: View? = null
    private var rowsContainer: LinearLayout? = null
    private var loadedOnce = false

    /** Lignes affichées, avec l'interrupteur associé, pour la navigation. */
    private class Row(val root: View, val toggle: Switch?, val entity: HAEntity)

    private val rows = mutableListOf<Row>()
    private var selectedIndex = 0

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
        ui = ContextThemeWrapper(this, R.style.Theme_HAShieldController)
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
            Toast.makeText(
                this,
                "Autorise « Affichage par-dessus les autres applications » pour HA Shield Control",
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return
        }

        val view = buildOverlay()
        overlayView = view

        val params = WindowManager.LayoutParams(
            overlayWidth(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(32)
            y = dp(32)
        }

        windowManager.addView(view, params)
        isShowing = true
        view.requestFocus()
        refresh()
        handler.postDelayed(refreshRunnable, REFRESH_MS)
    }

    private fun overlayWidth(): Int {
        val screenWidth = resources.displayMetrics.widthPixels
        return (screenWidth * 0.42f).toInt().coerceIn(dp(300), dp(460))
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
        loadedOnce = false
        isShowing = false
        rows.clear()
        selectedIndex = 0
    }

    private fun buildOverlay(): View {
        val root = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFF14161B.toInt(), 0xFF3E8EF7.toInt())
            setPadding(dp(20), dp(16), dp(20), dp(18))
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

        root.addView(TextView(ui).apply {
            text = "Home Assistant"
            setTextColor(0xFFF3F4F6.toInt())
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
        })

        root.addView(TextView(ui).apply {
            text = "▲▼ sélectionner · OK basculer · Retour fermer"
            setTextColor(0xFF9BA1AA.toInt())
            textSize = 12f
            setPadding(0, dp(2), 0, dp(10))
        })

        rowsContainer = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(rowsContainer)
        return root
    }

    private fun refresh() {
        val container = rowsContainer ?: return
        val wanted = settings.effectiveOverlayEntities()

        if (wanted.isEmpty()) {
            container.removeAllViews()
            container.addView(label("Aucune entité sélectionnée pour l'overlay", 0xFFFFC24B.toInt()))
            return
        }

        if (!loadedOnce) {
            container.removeAllViews()
            container.addView(label("Chargement…", 0xFF9BA1AA.toInt()))
        }

        api.fetchEntities { fetched ->
            // Le callback arrive sur un thread réseau : toute manipulation de vue
            // doit repasser sur le thread principal.
            handler.post {
                if (rowsContainer !== container) return@post
                if (fetched == null) {
                    container.removeAllViews()
                    container.addView(label("Connexion impossible — vérifie la session", 0xFFFFC24B.toInt()))
                    return@post
                }
                loadedOnce = true
                val entities = fetched.filter { wanted.contains(it.entityId) }
                container.removeAllViews()
                rows.clear()
                if (entities.isEmpty()) {
                    container.addView(label("Entités introuvables (${wanted.size} demandée(s))", 0xFF9BA1AA.toInt()))
                    return@post
                }
                selectedIndex = 0
                entities.forEach { entity ->
                    val holder = row(entity)
                    rows.add(holder)
                    container.addView(holder.root)
                }
                applyHighlight()
            }
        }
    }

    private fun row(entity: HAEntity): Row {
        val line = LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
        }

        val texts = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(ui).apply {
            text = entity.name
            setTextColor(0xFFF3F4F6.toInt())
            textSize = 16f
        })
        texts.addView(TextView(ui).apply {
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
        line.addView(texts)

        var switch: Switch? = null
        if (entity.type == "light") {
            val toggle = Switch(ui).apply {
                isChecked = entity.state == "on"
                thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(0xFF3E8EF7.toInt(), 0xFF2F333C.toInt())
                )
                setOnCheckedChangeListener { _, checked ->
                    api.callService(
                        "light",
                        if (checked) "turn_on" else "turn_off",
                        entity.entityId
                    )
                    handler.postDelayed({ refresh() }, 800)
                }
            }
            line.addView(toggle)
            switch = toggle
        }

        return Row(line, switch, entity)
    }

    /** Met en évidence la ligne sélectionnée à la télécommande. */
    private fun applyHighlight() {
        rows.forEachIndexed { index, item ->
            item.root.background = if (index == selectedIndex) {
                rounded(0xFF23324A.toInt(), 0xFF3E8EF7.toInt())
            } else {
                null
            }
            item.root.setPadding(dp(8), dp(4), dp(8), dp(4))
        }
    }

    /** Touches D-pad transmises par le service d'accessibilité. */
    private fun onExternalKey(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (rows.isNotEmpty()) {
                    selectedIndex = (selectedIndex + 1) % rows.size
                    applyHighlight()
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (rows.isNotEmpty()) {
                    selectedIndex = (selectedIndex - 1 + rows.size) % rows.size
                    applyHighlight()
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val item = rows.getOrNull(selectedIndex) ?: return
                val toggle = item.toggle ?: return
                toggle.isChecked = !toggle.isChecked
            }
            KeyEvent.KEYCODE_BACK -> hide()
        }
    }

    private fun label(text: String, color: Int): TextView = TextView(ui).apply {
        this.text = text
        setTextColor(color)
        textSize = 13f
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun rounded(fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
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
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
