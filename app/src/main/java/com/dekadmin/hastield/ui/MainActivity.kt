package com.dekadmin.hastield.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.dekadmin.hastield.BuildConfig
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
    var serverUrl by remember { mutableStateOf(settings.serverUrl) }
    var accessToken by remember { mutableStateOf(settings.accessToken) }
    var entities by remember { mutableStateOf<List<HAEntity>>(emptyList()) }
    var selectedEntities by remember { mutableStateOf(settings.getSelectedEntities()) }
    var isConnected by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Configuration requise") }
    var showConfig by remember { mutableStateOf(false) }

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
            Text(
                text = "HA Shield Control",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Button(onClick = { showConfig = !showConfig }) {
                Text(if (showConfig) "Retour au dashboard" else "Réglages")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (showConfig) {
            SettingsPane(
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
                },
                isConnected = isConnected,
                statusMessage = statusMessage
            )
        } else {
            DashboardPane(
                modifier = Modifier.weight(1f),
                api = api,
                entities = entities,
                selectedEntities = selectedEntities,
                isConnected = isConnected
            )
        }
    }
}

@Composable
fun SettingsPane(
    modifier: Modifier = Modifier,
    settings: SettingsManager,
    entities: List<HAEntity>,
    selectedEntities: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    accessToken: String,
    onAccessTokenChange: (String) -> Unit,
    onOAuthToken: (String) -> Unit,
    onImported: () -> Unit,
    isConnected: Boolean,
    statusMessage: String
) {
    val context = LocalContext.current
    val discovery = remember { HaDiscovery(context) }
    var servers by remember { mutableStateOf<List<DiscoveredServer>>(emptyList()) }
    var discovering by remember { mutableStateOf(false) }
    var showLogin by remember { mutableStateOf(false) }
    var editingUrl by remember { mutableStateOf(false) }
    var editingToken by remember { mutableStateOf(false) }
    var storageMessage by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            storageMessage = try {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(settings.exportJson().toByteArray())
                }
                "Configuration exportée"
            } catch (e: Exception) {
                "Échec de l'export : ${e.message}"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            storageMessage = try {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                if (settings.importJson(text)) {
                    onImported()
                    "Configuration importée"
                } else {
                    "Fichier de configuration invalide"
                }
            } catch (e: Exception) {
                "Échec de l'import : ${e.message}"
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { discovery.stop() }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(text = "Serveur Home Assistant", fontWeight = FontWeight.Bold, color = Color.White)
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = serverUrl.ifBlank { "(non défini)" },
                    color = Color.Cyan,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { editingUrl = true }) { Text("Modifier l'URL") }
            }
        }

        item {
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
        }

        if (servers.isNotEmpty()) {
            item { Text(text = "Serveurs trouvés :", fontSize = 12.sp, color = Color.LightGray) }
            items(servers, key = { it.url }) { server ->
                Text(
                    text = "${server.name} — ${server.url}",
                    color = Color.Cyan,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onServerUrlChange(server.url) }
                        .padding(vertical = 8.dp)
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "Authentification", fontWeight = FontWeight.Bold, color = Color.White)
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showLogin = true }) { Text("Connexion Home Assistant") }
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                        if (!text.isNullOrBlank()) onAccessTokenChange(text)
                    }
                ) {
                    Text("Coller un token")
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (accessToken.isBlank()) {
                        "Aucun token enregistré"
                    } else {
                        "Token enregistré (${accessToken.take(6)}…)"
                    },
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { editingToken = true }) { Text("Saisir un token") }
            }
        }

        item {
            Text(
                text = if (settings.canRefresh) {
                    "Session OAuth active (rafraîchissement automatique)"
                } else {
                    "Pas de session OAuth : token manuel, sans expiration connue"
                },
                fontSize = 12.sp,
                color = if (settings.canRefresh) Color.Green else Color.Gray
            )
            Text(
                text = statusMessage,
                fontSize = 12.sp,
                color = if (isConnected) Color.Green else Color.Yellow
            )
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Dashboard : ${entities.count { selectedEntities.contains(it.entityId) }} / ${entities.size}",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onSelectionChange(entities.map { it.entityId }.toSet()) }) {
                        Text("Tout")
                    }
                    TextButton(onClick = { onSelectionChange(emptySet()) }) {
                        Text("Aucun")
                    }
                }
            }
        }

        if (entities.isEmpty()) {
            item {
                Text(
                    text = "Aucune entité reçue pour le moment.",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }
        } else {
            items(entities, key = { it.entityId }) { entity ->
                EntitySelectRow(
                    entity = entity,
                    selected = selectedEntities.contains(entity.entityId),
                    onToggle = { checked ->
                        onSelectionChange(
                            if (checked) selectedEntities + entity.entityId
                            else selectedEntities - entity.entityId
                        )
                    }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "Sauvegarde de la configuration", fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "L'URL et les entités sélectionnées survivent aux mises à jour. " +
                    "Cet export sert de filet de sécurité si tu dois désinstaller l'application.",
                fontSize = 12.sp,
                color = Color.LightGray
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("ha-shield-config.json") }) {
                    Text("Exporter")
                }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                    Text("Importer")
                }
            }
            storageMessage?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = it, fontSize = 12.sp, color = Color.Yellow)
            }
        }

        if (!settings.secureStorageAvailable) {
            item {
                Text(
                    text = "Stockage chiffré indisponible sur cet appareil : les jetons devront être ressaisis, le reste est conservé.",
                    fontSize = 12.sp,
                    color = Color.Yellow
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(4.dp))
            UpdateSection()
        }
    }

    if (showLogin) {
        LoginDialog(
            serverUrl = serverUrl,
            settingsManager = settings,
            onAuthenticated = { token ->
                onOAuthToken(token)
                showLogin = false
            },
            onDismiss = { showLogin = false }
        )
    }

    if (editingUrl) {
        EditFieldDialog(
            title = "URL Home Assistant",
            initialValue = serverUrl,
            secret = false,
            onSave = { onServerUrlChange(it.trim()) },
            onDismiss = { editingUrl = false }
        )
    }

    if (editingToken) {
        EditFieldDialog(
            title = "Long-Lived Access Token",
            initialValue = "",
            secret = true,
            onSave = { if (it.isNotBlank()) onAccessTokenChange(it.trim()) },
            onDismiss = { editingToken = false }
        )
    }
}

@Composable
fun EntitySelectRow(entity: HAEntity, selected: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!selected) }
            .padding(vertical = 4.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entity.name, color = Color.White, fontSize = 15.sp)
            Text(
                text = "${entity.entityId} · ${entity.state}",
                color = Color.Gray,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Checkbox(checked = selected, onCheckedChange = onToggle)
    }
}

@Composable
fun UpdateSection() {
    val context = LocalContext.current
    val updater = remember { Updater(context) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    val check: () -> Unit = {
        checking = true
        message = null
        updater.check(BuildConfig.VERSION_CODE) { info ->
            checking = false
            update = info
            if (info == null) message = "Application à jour"
        }
    }

    LaunchedEffect(Unit) { check() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                color = Color.LightGray,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = check, enabled = !checking) {
                Text(if (checking) "Vérification…" else "Vérifier les mises à jour")
            }
        }

        val available = update
        if (available != null) {
            Button(
                onClick = { updater.downloadAndInstall(available) { state -> message = state } },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Mettre à jour vers ${available.versionName}")
            }
        }

        message?.let {
            Text(text = it, fontSize = 12.sp, color = Color.Yellow)
        }
    }
}

/**
 * Saisie volontairement explicite : sur une TV, un champ texte qui prend le
 * focus ouvre le clavier virtuel. Ici le clavier n'apparaît qu'après une action
 * de l'utilisateur (ouverture de la boîte de dialogue).
 */
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

@Composable
fun DashboardPane(
    modifier: Modifier = Modifier,
    api: HomeAssistantApi,
    entities: List<HAEntity>,
    selectedEntities: Set<String>,
    isConnected: Boolean
) {
    if (!isConnected) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Veuillez configurer le serveur et vous connecter dans les réglages.",
                color = Color.Gray
            )
        }
        return
    }

    val displayEntities = entities.filter { selectedEntities.contains(it.entityId) }
    if (displayEntities.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Aucun élément sélectionné. Ouvrez 'Réglages' pour en choisir.",
                color = Color.Yellow
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(displayEntities, key = { it.entityId }) { entity ->
            EntityCard(entity, api)
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
