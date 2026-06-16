package com.splitexpense.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.splitexpense.ui.theme.*

/**
 * Dialog that lets the user enter (or update) their Gemini API key.
 *
 * @param currentKey Pre-fills the field with the already-stored key (empty if none).
 * @param onSave     Called with the trimmed non-blank key when the user confirms.
 * @param onDismiss  Called when the user cancels or taps outside.
 */
@Composable
fun ApiKeyDialog(
    currentKey: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var key by remember(currentKey) { mutableStateOf(currentKey) }
    var showKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor  = Surface2,
        shape           = RoundedCornerShape(16.dp),
        icon = {
            Icon(Icons.Filled.Key, contentDescription = null, tint = NeonGreen)
        },
        title = {
            Text("Gemini API Key", color = Text1, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "The bill-scanning feature uses Google's Gemini AI. " +
                    "Each person needs their own free key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Text2
                )
                Text(
                    "Get one free at: aistudio.google.com/apikey",
                    style = MaterialTheme.typography.bodySmall,
                    color = NeonGreen
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label    = { Text("API Key", color = Text3,
                        style = MaterialTheme.typography.bodySmall) },
                    placeholder = { Text("AIzaSy...", color = Text3,
                        style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None
                                           else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showKey) "Hide key" else "Show key",
                                tint = Text3
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = NeonGreen,
                        unfocusedBorderColor = Border,
                        focusedLabelColor    = NeonGreen,
                        unfocusedLabelColor  = Text3,
                        cursorColor          = NeonGreen,
                        focusedTextColor     = Text1,
                        unfocusedTextColor   = Text1
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick  = { if (key.isNotBlank()) onSave(key.trim()) },
                enabled  = key.isNotBlank()
            ) {
                Text("Save", color = NeonGreen, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Text3)
            }
        }
    )
}
