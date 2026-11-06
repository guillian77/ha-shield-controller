package com.dekadmin.hastield.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.dekadmin.hastield.BuildConfig
import com.dekadmin.hastield.R
import com.dekadmin.hastield.ha.DiscoveredServer
import com.dekadmin.hastield.ha.HAEntity
import com.dekadmin.hastield.ha.HaAuth
import com.dekadmin.hastield.ha.HaDiscovery
import com.dekadmin.hastield.ha.HomeAssistantApi
import com.dekadmin.hastield.ha.SettingsManager
import com.dekadmin.hastield.update.UpdateInfo
import com.dekadmin.hastield.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private lateinit var settings: SettingsManager
    private lateinit var api: HomeAssistantApi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsManager(this)
        api = HomeAssistantApi(settings)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = AppColors.accent,
                    onPrimary = Color.White,
                    background = AppColors.background,
                    onBackground = AppColors.textPrimary,
                    surface = AppColors.surface,
                    onSurface = AppColors.textPrimary,
                    surfaceVariant = AppColors.surfaceHigh,
                    onSurfaceVariant = AppColors.textSecondary,
                    outline = AppColors.outline
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = AppColors.background
                ) {
                    MainScreen(settings, api)
                }
            }
        }
    }

    private var lastHomePressTime = 0L
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_BUTTON_MODE || keyCode == KeyEvent.KEYCODE_STEM_1) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastHomePressTime < 500) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
                return true
            }
            lastHomePressTime = currentTime
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
fun MainScreen(settings: SettingsManager, api: HomeAssistantApi) {
    var serverUrl by remember { mutableStateOf(settings.serverUrl) }
    var accessToken by remember { mutableStateOf(settings.accessToken) }
    var entities by remember { mutableStateOf<List<HAEntity>>(emptyList()) }
    var selectedEntities by remember { mutableStateOf(settings.getSelectedEntities()) }
    var isConnected by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Configuration requise") }
    var showConfig by remember { mutableStateOf(false) }
    var dashboardOrder by remember { mutableStateOf(settings.dashboardOrder) }

    // Retour : quitte les réglages pour revenir au dashboard au lieu de
    // fermer l'activité.
    BackHandler(enabled = showConfig) { showConfig = false }

    val context = LocalContext.current
    val updater = remember { Updater(context) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        updater.check(BuildConfig.VERSION_CODE) { info -> updateInfo = info }
    }

    LaunchedEffect(accessToken) {
        if (accessToken.isNotBlank()) {
            while (true) {
                withContext(Dispatchers.IO) {
                    api.fetchEntities { fetched ->
                        if (fetched != null) {
                            entities = fetched
                            isConnected = true
                            statusMessage = "Connecté (${fetched.size} entités détectées)"
                        } else {
                            isConnected = false
                            statusMessage = "Connexion impossible : vérifie l'URL et l'authentification"
                        }
                    }
                }
                delay(5000)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "HA Shield Control",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.textPrimary
                )
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    fontSize = 11.sp,
                    color = AppColors.textSecondary
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val available = updateInfo
                if (available != null) {
                    Button(
                        onClick = {
                            updater.downloadAndInstall(available) { msg -> updateMessage = msg }
                        }
                    ) {
                        Text("Mettre à jour ${available.versionName}")
                    }
                }
                Button(onClick = { showConfig = !showConfig }) {
                    Text(if (showConfig) "Retour au dashboard" else "Réglages")
                }
            }
        }

        updateMessage?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = it, fontSize = 12.sp, color = Color.Yellow)
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (showConfig) {
            SettingsScreen(
                modifier = Modifier.weight(1f),
                settings = settings,
                entities = entities,
                selectedEntities = selectedEntities,
                onSelectionChange = {
                    selectedEntities = it
                    settings.saveSelectedEntities(it)
                },
                serverUrl = serverUrl,
                onServerUrlChange = {
                    serverUrl = it
                    settings.serverUrl = it
                },
                accessToken = accessToken,
                onAccessTokenChange = {
                    accessToken = it
                    settings.accessToken = it
                    settings.refreshToken = ""
                    settings.accessTokenExpiresAt = 0L
                },
                onOAuthToken = { token ->
                    // Les jetons (dont le refresh_token) ont déjà été écrits par HaAuth.
                    accessToken = token
                },
                onImported = {
                    serverUrl = settings.serverUrl
                    accessToken = settings.accessToken
                    selectedEntities = settings.getSelectedEntities()
                    dashboardOrder = settings.dashboardOrder
                },
                isConnected = isConnected,
                statusMessage = statusMessage,
                order = dashboardOrder,
                onOrderChange = { dashboardOrder = it }
            )
        } else {
            DashboardPane(
                modifier = Modifier.weight(1f),
                api = api,
                entities = entities,
                selectedEntities = selectedEntities,
                isConnected = isConnected,
                order = dashboardOrder
            )
        }
    }
}

@Composable
fun EditFieldDialog(
    title: String,
    initialValue: String,
    secret: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initialValue) }
    val focusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }) { Text("Enregistrer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

/** Trie les entités selon l'ordre choisi, puis alphabétiquement à défaut. */
private fun entityComparator(order: List<String>): Comparator<HAEntity> {
    val index = order.withIndex().associate { it.value to it.index }
    return compareBy({ index[it.entityId] ?: Int.MAX_VALUE }, { it.name.lowercase() })
}

@Composable
fun DashboardPane(
    modifier: Modifier = Modifier,
    api: HomeAssistantApi,
    entities: List<HAEntity>,
    selectedEntities: Set<String>,
    isConnected: Boolean,
    order: List<String> = emptyList()
) {
    if (!isConnected) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            DashboardHint(
                text = "Serveur non connecté — ouvre « Réglages » pour configurer l'accès à Home Assistant."
            )
        }
        return
    }

    val displayEntities = entities.filter { selectedEntities.contains(it.entityId) }
    if (displayEntities.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            DashboardHint(
                text = "Aucune entité sélectionnée — ouvre « Réglages » pour en choisir."
            )
        }
        return
    }

    val thermostats = displayEntities
        .filter { it.type == "thermostat" }
        .sortedWith(entityComparator(order))
    val lights = displayEntities
        .filter { it.type == "light" }
        .sortedWith(entityComparator(order))

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        if (thermostats.isNotEmpty()) {
            item { SectionHeader("Chauffage", R.drawable.ic_entity_thermostat) }
            items(thermostats, key = { it.entityId }) { entity ->
                ClimateCard(entity, api)
            }
        }
        if (lights.isNotEmpty()) {
            item { SectionHeader("Éclairage", R.drawable.ic_entity_light) }
            items(lights, key = { it.entityId }) { entity ->
                LightCard(entity, api)
            }
        }
    }
}

/**
 * Ouvre la page de login de Home Assistant dans une WebView intégrée : une fois
 * l'utilisateur connecté, les jetons OAuth que le frontend stocke dans
 * `localStorage.hassTokens` sont récupérés puis enregistrés.
 */
@Composable
fun LoginDialog(
    serverUrl: String,
    settingsManager: SettingsManager,
    onAuthenticated: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val haAuth = remember { HaAuth(settingsManager) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 400.dp, max = 640.dp)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Connexion à Home Assistant", fontWeight = FontWeight.Bold)
                    TextButton(onClick = onDismiss) { Text("Fermer") }
                }
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(520.dp),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            this.settings.javaScriptEnabled = true
                            this.settings.domStorageEnabled = true
                            this.settings.useWideViewPort = true
                            this.settings.loadWithOverviewMode = true
                            webViewClient = WebViewClient()
                            loadUrl(serverUrl.trimEnd('/'))
                            webView = this
                        }
                    }
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
    }

    LaunchedEffect(serverUrl) {
        val js = "(function(){try{return window.localStorage.getItem('hassTokens')||'';}catch(e){return '';}})()"
        repeat(120) {
            delay(2000)
            val view = webView ?: return@repeat
            val json = parseHassTokens(readHassTokens(view, js))
            if (json != null && haAuth.acceptFrontendTokens(json)) {
                onAuthenticated(settingsManager.accessToken)
                return@LaunchedEffect
            }
        }
    }
}

private suspend fun readHassTokens(view: WebView, js: String): String? =
    suspendCancellableCoroutine { continuation ->
        view.evaluateJavascript(js) { result ->
            if (continuation.isActive) continuation.resume(result)
        }
    }

/** Décode la valeur renvoyée par evaluateJavascript (chaîne JSON échappée). */
private fun parseHassTokens(raw: String?): JSONObject? {
    if (raw.isNullOrBlank() || raw == "null") return null
    return try {
        val value = JSONTokener(raw).nextValue()
        val text = when (value) {
            is String -> value
            else -> value.toString()
        }
        if (text.isBlank()) null else JSONObject(text)
    } catch (e: Exception) {
        null
    }
}
