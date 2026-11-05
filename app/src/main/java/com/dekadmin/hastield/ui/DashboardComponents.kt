package com.dekadmin.hastield.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dekadmin.hastield.R
import com.dekadmin.hastield.ha.HAEntity
import com.dekadmin.hastield.ha.HomeAssistantApi
import org.json.JSONArray
import java.util.Locale

/** Palette inspirée du tableau de bord Home Assistant (sombre, accent bleu). */
object AppColors {
    val background = Color(0xFF0D0E11)
    val surface = Color(0xFF1A1C21)
    val surfaceHigh = Color(0xFF23262D)
    val outline = Color(0xFF2F333C)
    val accent = Color(0xFF3E8EF7)
    val amber = Color(0xFFFFC24B)
    val textPrimary = Color(0xFFF3F4F6)
    val textSecondary = Color(0xFF9BA1AA)
}

private fun Map<String, Any>.asDouble(key: String): Double? =
    (this[key] as? Number)?.toDouble()

private fun Map<String, Any>.asStringList(key: String): List<String> {
    val value = this[key] ?: return emptyList()
    return when (value) {
        is JSONArray -> (0 until value.length())
            .mapNotNull { value.optString(it).takeIf(String::isNotBlank) }
        is List<*> -> value.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
        is String -> value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }
}

private fun formatTemp(value: Double?): String =
    if (value == null) "--" else String.format(Locale.US, "%.1f", value)

@Composable
fun SectionHeader(title: String, iconRes: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = AppColors.textSecondary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title.uppercase(Locale.FRENCH),
            color = AppColors.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp
        )
    }
}

/** Pastille d'icône colorée à gauche d'une carte. */
@Composable
private fun EntityBadge(iconRes: Int, tint: Color) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(tint.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** Conteneur de carte commun. */
@Composable
private fun EntitySurface(content: @Composable RowScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = AppColors.surface,
        border = BorderStroke(1.dp, AppColors.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

@Composable
fun LightCard(entity: HAEntity, api: HomeAssistantApi) {
    val isOn = entity.state == "on"
    EntitySurface {
        EntityBadge(
            iconRes = R.drawable.ic_entity_light,
            tint = if (isOn) AppColors.amber else AppColors.textSecondary
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entity.name,
                color = AppColors.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (isOn) "Allumée" else "Éteinte",
                color = AppColors.textSecondary,
                fontSize = 12.sp
            )
        }
        Switch(
            checked = isOn,
            onCheckedChange = { checked ->
                api.callService(
                    "light",
                    if (checked) "turn_on" else "turn_off",
                    entity.entityId
                )
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AppColors.accent,
                uncheckedThumbColor = AppColors.textSecondary,
                uncheckedTrackColor = AppColors.surfaceHigh,
                uncheckedBorderColor = AppColors.outline
            )
        )
    }
}

@Composable
fun ClimateCard(entity: HAEntity, api: HomeAssistantApi) {
    val attrs = entity.attributes
    val current = attrs.asDouble("current_temperature")
    val target = attrs.asDouble("temperature")
    val step = attrs.asDouble("target_temp_step") ?: 0.5
    val minTemp = attrs.asDouble("min_temp") ?: 7.0
    val maxTemp = attrs.asDouble("max_temp") ?: 35.0
    val presetModes = attrs.asStringList("preset_modes")
    val activePreset = attrs["preset_mode"] as? String

    fun setTarget(delta: Double) {
        val base = target ?: current ?: return
        var next = kotlin.math.round((base + delta) / step) * step
        next = next.coerceIn(minTemp, maxTemp)
        api.callService("climate", "set_temperature", entity.entityId, mapOf("temperature" to next))
    }

    EntitySurface {
        EntityBadge(iconRes = R.drawable.ic_entity_thermostat, tint = AppColors.accent)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entity.name,
                color = AppColors.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Actuel ${formatTemp(current)}°C · ${entity.state}",
                color = AppColors.textSecondary,
                fontSize = 12.sp
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundIconButton(label = "−", onClick = { setTarget(-step) })
            TempBadge(text = "${formatTemp(target)}°C")
            RoundIconButton(label = "+", onClick = { setTarget(step) })
        }

        if (presetModes.isNotEmpty()) {
            Spacer(Modifier.width(12.dp))
            LazyRow(
                modifier = Modifier.widthIn(max = 360.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(presetModes, key = { it }) { mode ->
                    PillButton(
                        label = mode,
                        active = mode == activePreset,
                        onClick = {
                            api.callService(
                                "climate",
                                "set_preset_mode",
                                entity.entityId,
                                mapOf("preset_mode" to mode)
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TempBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AppColors.surfaceHigh,
        border = BorderStroke(1.dp, AppColors.outline)
    ) {
        Text(
            text = text,
            color = AppColors.textPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(min = 76.dp)
                .padding(vertical = 8.dp)
        )
    }
}

@Composable
private fun RoundIconButton(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        shape = CircleShape,
        color = if (focused) AppColors.accent.copy(alpha = 0.25f) else AppColors.surfaceHigh,
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) AppColors.accent else AppColors.outline),
        modifier = Modifier
            .size(40.dp)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = label, color = AppColors.textPrimary, fontSize = 20.sp)
        }
    }
}

@Composable
private fun PillButton(label: String, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        shape = RoundedCornerShape(50),
        color = when {
            active -> AppColors.accent.copy(alpha = 0.20f)
            focused -> AppColors.surfaceHigh
            else -> AppColors.surfaceHigh
        },
        border = BorderStroke(
            if (focused) 2.dp else 1.dp,
            when {
                focused -> AppColors.accent
                active -> AppColors.accent.copy(alpha = 0.7f)
                else -> AppColors.outline
            }
        ),
        modifier = Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick
        )
    ) {
        Text(
            text = label,
            color = if (active) AppColors.accent else AppColors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

/** Messages centrés du dashboard (hors connexion, aucune sélection). */
@Composable
fun DashboardHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = AppColors.surface,
            border = BorderStroke(1.dp, AppColors.outline)
        ) {
            Text(
                text = text,
                color = AppColors.textSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)
            )
        }
    }
}
