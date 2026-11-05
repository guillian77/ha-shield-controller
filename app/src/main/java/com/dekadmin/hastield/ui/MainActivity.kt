package com.dekadmin.hastield.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.dekadmin.hastield.ha.DiscoveredServer
import com.dekadmin.hastield.ha.HAEntity
import com.dekadmin.hastield.ha.HaAuth
import com.dekadmin.hastield.ha.HaDiscovery
import com.dekadmin.hastield.ha.HomeAssistantApi
import com.dekadmin.hastield.ha.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                MainScreen(settings, api)
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
    val context = LocalContext.current

    var serverUrl by remember { mutableStateOf(settings.serverUrl) }
    var accessToken by remember { mutableStateOf(settings.accessToken) }
    var entities by remember { mutableStateOf<List<HAEntity>>(emptyList()) }
    var selectedEntities by remember { mutableStateOf(settings.getSelectedEntities()) }
    var isConnected by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Configuration requise") }
    var showConfig by remember { mutableStateOf(false) }

    var servers by remember { mutableStateOf<List<DiscoveredServer>>(emptyList()) }
    var discovering by remember { mutableStateOf(false) }
    var showLogin by remember { mutableStateOf(false) }

    val discovery = remember { HaDiscovery(context) }
    DisposableEffect(Unit) {
        onDispose { discovery.stop() }
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
            .padding(24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "HA Shield Control", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Button(onClick = { showConfig = !showConfig }) {
                Text(if (showConfig) "Fermer Paramètres" else "Configuration & Sélection")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (showConfig) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Serveur Home Assistant", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it.trim(); settings.serverUrl = serverUrl },
                        label = { Text("URL (ex: http://192.168.1.50:8123)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                servers = emptyList()
                                discovering = true
                                discovery.start(
                                    durationMs = 8000,
                                    onFound = { server ->
                                        if (servers.none { it.url == server.url }) {
                                            servers = servers + server
                                        }
                                    },
                                    onFinished = { discovering = false }
                                )
                            },
                            enabled = !discovering
                        ) {
                            Text(if (discovering) "Recherche…" else "Détecter sur le réseau")
                        }
                        if (discovering) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }

                    if (servers.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Serveurs trouvés :", fontSize = 12.sp)
                        servers.forEach { server ->
                            Text(
                                text = "${server.name} — ${server.url}",
                                color = Color.Cyan,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        serverUrl = server.url
                                        settings.serverUrl = server.url
                                    }
                                    .padding(vertical = 6.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "Authentification", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showLogin = true }) {
                            Text("Connexion Home Assistant")
                        }
                        OutlinedButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                                if (!text.isNullOrBlank()) {
                                    accessToken = text
                                    settings.accessToken = text
                                    settings.refreshToken = ""
                                    settings.accessTokenExpiresAt = 0L
                                }
                            }
                        ) {
                            Text("Coller un token")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = accessToken,
                        onValueChange = {
                            accessToken = it.trim()
                            settings.accessToken = accessToken
                            settings.refreshToken = ""
                            settings.accessTokenExpiresAt = 0L
                        },
                        label = { Text("Long-Lived Access Token (optionnel)") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = if (settings.canRefresh) "Session OAuth active (rafraîchissement automatique)"
                        else "Pas de session OAuth : token manuel",
                        fontSize = 12.sp,
                        color = if (settings.canRefresh) Color.Green else Color.Gray
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = statusMessage,
                        fontSize = 12.sp,
                        color = if (isConnected) Color.Green else Color.Yellow
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "Sélection des éléments à afficher sur le Dashboard :", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(entities) { entity ->
                            val isSelected = selectedEntities.contains(entity.entityId)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedEntities = if (isSelected) {
                                            selectedEntities - entity.entityId
                                        } else {
                                            selectedEntities + entity.entityId
                                        }
                                        settings.saveSelectedEntities(selectedEntities)
                                    }
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${entity.name} (${entity.entityId})",
                                    color = Color.White,
                                    fontSize = 14.sp
                                )
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        selectedEntities = if (checked) {
                                            selectedEntities + entity.entityId
                                        } else {
                                            selectedEntities - entity.entityId
                                        }
                                        settings.saveSelectedEntities(selectedEntities)
                                    }
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(text = "Dashboard Rapide", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))

        if (!isConnected) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Veuillez configurer le serveur et vous connecter dans le menu de configuration.",
                    color = Color.Gray
                )
            }
        } else {
            val displayEntities = entities.filter { selectedEntities.contains(it.entityId) }
            if (displayEntities.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Aucun élément sélectionné. Ouvrez 'Configuration & Sélection' pour en choisir.",
                        color = Color.Yellow
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(displayEntities) { entity ->
                        EntityCard(entity, api)
                    }
                }
            }
        }
    }

    if (showLogin) {
        LoginDialog(
            serverUrl = serverUrl,
            settingsManager = settings,
            onAuthenticated = { token ->
                accessToken = token
                statusMessage = "Authentification réussie"
                showLogin = false
            },
            onDismiss = { showLogin = false }
        )
    }
}

/**
 * Ouvre la page de login de Home Assistant dans une WebView intégrée : une fois
 * l'utilisateur connecté, les jetons OAuth que le frontend stocke dans
 * `localStorage.hassTokens` sont récupérés puis enregistrés. Plus besoin de
 * recopier un long-lived token sur la Shield.
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

    // Après login, le frontend stocke ses jetons dans localStorage["hassTokens"].
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

@Composable
fun EntityCard(entity: HAEntity, api: HomeAssistantApi) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = entity.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(text = "État: ${entity.state}", fontSize = 14.sp, color = Color.LightGray)
            }

            if (entity.type == "light") {
                val isOn = entity.state == "on"
                Switch(
                    checked = isOn,
                    onCheckedChange = { checked ->
                        val service = if (checked) "turn_on" else "turn_off"
                        api.callService("light", service, entity.entityId)
                    }
                )
            } else if (entity.type == "thermostat") {
                val currentTemp = entity.attributes["current_temperature"] ?: "--"
                val targetTemp = entity.attributes["temperature"] ?: "--"
                Text(
                    text = "Act: $currentTemp°C | Cible: $targetTemp°C",
                    color = Color.Cyan,
                    fontSize = 14.sp
                )
            }
        }
    }
}
