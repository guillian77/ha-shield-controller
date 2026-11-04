package com.dekadmin.hastield.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dekadmin.hastield.ha.HAEntity
import com.dekadmin.hastield.ha.HomeAssistantApi
import com.dekadmin.hastield.ha.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
                        if (fetched.isNotEmpty()) {
                            entities = fetched
                            isConnected = true
                            statusMessage = "Connecté (${fetched.size} entités détectées)"
                        } else {
                            statusMessage = "Erreur de connexion / Token invalide"
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
                    Text(text = "Paramètres du Serveur & Sécurité", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it; settings.serverUrl = it },
                        label = { Text("URL Home Assistant (ex: http://192.168.1.50:8123)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = accessToken,
                        onValueChange = { accessToken = it; settings.accessToken = it },
                        label = { Text("Long-Lived Access Token") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = statusMessage, fontSize = 12.sp, color = if (isConnected) Color.Green else Color.Yellow)

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "Sélection des éléments à afficher sur le Dashboard :", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(modifier = Modifier.height(200.dp)) {
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
                                Text(text = "${entity.name} (${entity.entityId})", color = Color.White, fontSize = 14.sp)
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
                Text(text = "Veuillez configurer l'URL et le Token dans le menu de configuration.", color = Color.Gray)
            }
        } else {
            val displayEntities = entities.filter { selectedEntities.contains(it.entityId) }
            if (displayEntities.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "Aucun élément sélectionné. Ouvrez 'Configuration & Sélection' pour en choisir.", color = Color.Yellow)
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
                Text(text = "Act: $currentTemp°C | Cible: $targetTemp°C", color = Color.Cyan, fontSize = 14.sp)
            }
        }
    }
}
