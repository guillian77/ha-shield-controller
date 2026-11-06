package com.dekadmin.hastield.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dekadmin.hastield.BuildConfig
import com.dekadmin.hastield.R
import com.dekadmin.hastield.ha.DiscoveredServer
import com.dekadmin.hastield.ha.HAEntity
import com.dekadmin.hastield.ha.HaDiscovery
import com.dekadmin.hastield.ha.SettingsManager
import com.dekadmin.hastield.update.UpdateInfo
import com.dekadmin.hastield.update.Updater
import java.util.Locale

private val connectedGreen = Color(0xFF4CD964)

@Composable
fun SettingsScreen(
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
    statusMessage: String,
    order: List<String>,
    onOrderChange: (List<String>) -> Unit
) {
    val context = LocalContext.current
    val discovery = remember { HaDiscovery(context) }
    var servers by remember { mutableStateOf<List<DiscoveredServer>>(emptyList()) }
    var discovering by remember { mutableStateOf(false) }
    var showLogin by remember { mutableStateOf(false) }
    var editingUrl by remember { mutableStateOf(false) }
    var editingToken by remember { mutableStateOf(false) }
    var storageMessage by remember { mutableStateOf<String?>(null) }

    // Replié par défaut dès qu'une session est active : la configuration
    // serveur/authentification n'a d'intérêt qu'en cas de problème.
    var connectionExpanded by remember { mutableStateOf(!isConnected || accessToken.isBlank()) }

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
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            ConnectionCard(
                serverUrl = serverUrl,
                isConnected = isConnected,
                statusMessage = statusMessage,
                expanded = connectionExpanded,
                onToggle = { connectionExpanded = !connectionExpanded }
            )
        }

        if (connectionExpanded) {
            item {
                SettingsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = serverUrl.ifBlank { "(non défini)" },
                            color = AppColors.textPrimary,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = { editingUrl = true }) { Text("Modifier") }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (discovering) {
                                    discovery.stop()
                                    discovering = false
                                } else {
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
                                }
                            }
                        ) {
                            Text(if (discovering) "Arrêter la recherche" else "Détecter sur le réseau")
                        }
                        if (discovering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = AppColors.accent,
                                strokeWidth = 2.dp
                            )
                        }
                    }
                    if (servers.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        servers.forEach { server ->
                            Text(
                                text = "${server.name} — ${server.url}",
                                color = AppColors.accent,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onServerUrlChange(server.url) }
                                    .padding(vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            item {
                SettingsCard {
                    Text(
                        text = "Authentification",
                        color = AppColors.textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(10.dp))
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
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (accessToken.isBlank()) "Aucun token" else "Token (${accessToken.take(6)}…)",
                            color = AppColors.textSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { editingToken = true }) { Text("Saisir") }
                    }
                    Text(
                        text = if (settings.canRefresh) {
                            "Session OAuth — rafraîchissement automatique"
                        } else {
                            "Token manuel — sans expiration connue"
                        },
                        color = if (settings.canRefresh) connectedGreen else AppColors.textSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }

        item { SectionHeader("Dashboard", R.drawable.ic_entity_light) }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${entities.count { selectedEntities.contains(it.entityId) }} / ${entities.size} sélectionnée(s)",
                    color = AppColors.textSecondary,
                    fontSize = 13.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        val ids = entities.map { it.entityId }.toSet()
                        ids.forEach { settings.ensureInOrder(it) }
                        onSelectionChange(ids)
                        onOrderChange(settings.dashboardOrder)
                    }) {
                        Text("Tout")
                    }
                    TextButton(onClick = { onSelectionChange(emptySet()) }) {
                        Text("Aucun")
                    }
                }
            }
        }

        if (entities.isEmpty()) {
            item { SettingsHint("Aucune entité reçue pour le moment.") }
        } else {
            val orderIndex = order.withIndex().associate { it.value to it.index }
            val orderedSelected = entities
                .filter { selectedEntities.contains(it.entityId) }
                .sortedWith(compareBy({ orderIndex[it.entityId] ?: Int.MAX_VALUE }, { it.name }))
            val others = entities
                .filterNot { selectedEntities.contains(it.entityId) }
                .sortedBy { it.name }

            items(orderedSelected, key = { "sel-" + it.entityId }) { entity ->
                EntitySelectRow(
                    entity = entity,
                    selected = true,
                    onToggle = { checked ->
                        if (!checked) onSelectionChange(selectedEntities - entity.entityId)
                    },
                    onMoveUp = {
                        settings.moveInOrder(entity.entityId, -1)
                        onOrderChange(settings.dashboardOrder)
                    },
                    onMoveDown = {
                        settings.moveInOrder(entity.entityId, 1)
                        onOrderChange(settings.dashboardOrder)
                    }
                )
            }

            if (others.isNotEmpty()) {
                item { SettingsHint("Non affichées") }
                items(others, key = { "off-" + it.entityId }) { entity ->
                    EntitySelectRow(
                        entity = entity,
                        selected = false,
                        onToggle = { checked ->
                            if (checked) {
                                onSelectionChange(selectedEntities + entity.entityId)
                                settings.ensureInOrder(entity.entityId)
                                onOrderChange(settings.dashboardOrder)
                            }
                        }
                    )
                }
            }
        }

        item { SectionHeader("Sauvegarde", R.drawable.ic_settings_save) }

        item {
            SettingsCard {
                Text(
                    text = "L'URL et la sélection survivent aux mises à jour. Cet export sert de " +
                        "filet de sécurité si l'application doit être désinstallée.",
                    color = AppColors.textSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportLauncher.launch("ha-shield-config.json") }) {
                        Text("Exporter")
                    }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
                    ) {
                        Text("Importer")
                    }
                }
                storageMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(text = it, color = AppColors.amber, fontSize = 12.sp)
                }
            }
        }

        item { SectionHeader("Mise à jour", R.drawable.ic_settings_update) }
        item { UpdateCard() }
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
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = AppColors.surface,
        border = BorderStroke(1.dp, AppColors.outline)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun SettingsHint(text: String) {
    Text(
        text = text,
        color = AppColors.textSecondary,
        fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
    )
}

@Composable
private fun ConnectionCard(
    serverUrl: String,
    isConnected: Boolean,
    statusMessage: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (isConnected) connectedGreen else AppColors.amber)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isConnected) "Connecté à Home Assistant" else "Non connecté",
                    color = AppColors.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = serverUrl.removePrefix("http://").removePrefix("https://").trimEnd('/'),
                    color = AppColors.textSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
            }
            OutlinedButton(onClick = onToggle) {
                Text(if (expanded) "Réduire" else "Afficher plus")
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = statusMessage,
                color = if (isConnected) connectedGreen else AppColors.amber,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun EntitySelectRow(
    entity: HAEntity,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!selected) },
        shape = RoundedCornerShape(14.dp),
        color = if (selected) AppColors.accent.copy(alpha = 0.10f) else AppColors.surface,
        border = BorderStroke(
            1.dp,
            if (selected) AppColors.accent.copy(alpha = 0.45f) else AppColors.outline
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entity.name,
                    color = AppColors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${entity.entityId} · ${entity.state}",
                    color = AppColors.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (onMoveUp != null) {
                ArrowButton(label = "↑", onClick = onMoveUp)
                Spacer(Modifier.width(6.dp))
            }
            if (onMoveDown != null) {
                ArrowButton(label = "↓", onClick = onMoveDown)
                Spacer(Modifier.width(8.dp))
            }
            Checkbox(
                checked = selected,
                onCheckedChange = onToggle,
                colors = CheckboxDefaults.colors(
                    checkedColor = AppColors.accent,
                    uncheckedColor = AppColors.textSecondary,
                    checkmarkColor = Color.White
                )
            )
        }
    }
}

@Composable
private fun ArrowButton(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = AppColors.surfaceHigh,
        border = BorderStroke(1.dp, AppColors.outline),
        modifier = Modifier
            .size(34.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = label, color = AppColors.textPrimary, fontSize = 16.sp)
        }
    }
}

@Composable
private fun UpdateCard() {
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

    SettingsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                color = AppColors.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = check) {
                Text(if (checking) "Vérification…" else "Vérifier")
            }
        }

        val available = update
        if (available != null) {
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { updater.downloadAndInstall(available) { state -> message = state } },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Mettre à jour vers ${available.versionName}")
            }
        }

        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(text = it, fontSize = 12.sp, color = AppColors.amber)
        }
    }
}
