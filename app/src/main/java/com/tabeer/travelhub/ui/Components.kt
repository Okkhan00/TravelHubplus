package com.tabeer.travelhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tabeer.travelhub.data.Portal

val PortalPalette = listOf("#1565C0", "#00897B", "#6A1B9A", "#C62828", "#EF6C00", "#2E7D32", "#37474F", "#AD1457")

fun parseColor(hex: String): Color? = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: Exception) {
    null
}

fun portalColor(p: Portal): Color =
    parseColor(p.colorHex) ?: parseColor(PortalPalette[(p.name.hashCode().and(0x7fffffff)) % PortalPalette.size]) ?: Color(0xFF1565C0)

@Composable
fun PortalAvatar(p: Portal, size: Dp = 48.dp) {
    val label = p.iconText.ifBlank { p.name.trim().take(1).uppercase() }
    Box(
        Modifier.size(size).clip(CircleShape).background(portalColor(p)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
    }
}

/**
 * Generic numeric-PIN dialog with one masked field per label. Validation is done by the caller,
 * which passes [error] back. PINs are never logged or kept after the dialog closes.
 */
@Composable
fun PinEntryDialog(
    title: String,
    labels: List<String>,
    confirmText: String,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (List<String>) -> Unit,
    extraLabel: String? = null,
    onExtra: (() -> Unit)? = null,
) {
    val values = remember { mutableStateListOf(*Array(labels.size) { "" }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { i, label ->
                    OutlinedTextField(
                        value = values[i],
                        onValueChange = { v -> values[i] = v.filter { it.isDigit() }.take(12) },
                        label = { Text(label) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = error != null,
                    )
                }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (extraLabel != null && onExtra != null) TextButton(onClick = onExtra) { Text(extraLabel) }
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(values.toList()) }) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

fun formatTime(ms: Long): String = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(ms))

val DefaultCorner = RoundedCornerShape(16.dp)
