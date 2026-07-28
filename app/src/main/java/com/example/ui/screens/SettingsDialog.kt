package com.example.ui.screens

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    initialUrl: String,
    initialUser: String,
    initialPass: String,
    initialFolder: String,
    onDismiss: () -> Unit,
    onSave: (url: String, user: String, pass: String, folder: String) -> Unit,
    onAutoDetect: () -> Unit = {}
) {
    val context = LocalContext.current
    var url    by remember { mutableStateOf(initialUrl) }
    var user   by remember { mutableStateOf(initialUser) }
    var pass   by remember { mutableStateOf(initialPass) }
    var folder by remember { mutableStateOf(initialFolder) }
    var showPass by remember { mutableStateOf(false) }

    // SAF folder picker
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val resolved = getPhysicalPathFromUri(context, uri)
            if (resolved != null) {
                folder = resolved
                Toast.makeText(context, "Dossier : $resolved", Toast.LENGTH_LONG).show()
            } else {
                folder = uri.toString()
                Toast.makeText(context, "Dossier (URI) configuré.", Toast.LENGTH_LONG).show()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Paramètres",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ── Server URL ──────────────────────────────────────────────
                OutlinedTextField(
                    value         = url,
                    onValueChange = { url = it },
                    label         = { Text("URL du serveur") },
                    leadingIcon   = { Icon(Icons.Default.Web, null) },
                    modifier      = Modifier.fillMaxWidth().testTag("server_url_input"),
                    singleLine    = true
                )

                // ── Username ────────────────────────────────────────────────
                OutlinedTextField(
                    value         = user,
                    onValueChange = { user = it },
                    label         = { Text("Nom d'utilisateur") },
                    leadingIcon   = { Icon(Icons.Default.Person, null) },
                    modifier      = Modifier.fillMaxWidth().testTag("username_input"),
                    singleLine    = true
                )

                // ── Password ────────────────────────────────────────────────
                OutlinedTextField(
                    value         = pass,
                    onValueChange = { pass = it },
                    label         = { Text("Mot de passe") },
                    leadingIcon   = { Icon(Icons.Default.Lock, null) },
                    trailingIcon  = {
                        IconButton(onClick = { showPass = !showPass }) {
                            Icon(
                                if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                null
                            )
                        }
                    },
                    visualTransformation = if (showPass)
                        androidx.compose.ui.text.input.VisualTransformation.None
                    else
                        androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier   = Modifier.fillMaxWidth().testTag("password_input"),
                    singleLine = true
                )

                // ── Folder ──────────────────────────────────────────────────
                Text(
                    "Dossier surveillé",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value         = folder,
                    onValueChange = { folder = it },
                    label         = { Text("Chemin") },
                    leadingIcon   = { Icon(Icons.Default.Folder, null) },
                    modifier      = Modifier.fillMaxWidth().testTag("monitor_folder_input"),
                    singleLine    = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Auto-detect
                    OutlinedButton(
                        onClick  = {
                            onAutoDetect()
                            // Refresh displayed folder after detection
                        },
                        modifier = Modifier.weight(1f),
                        shape    = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Auto-détecter", style = MaterialTheme.typography.labelSmall)
                    }

                    // Browse SAF
                    OutlinedButton(
                        onClick  = { folderLauncher.launch(null) },
                        modifier = Modifier.weight(1f).testTag("browse_folder_button"),
                        shape    = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Parcourir", style = MaterialTheme.typography.labelSmall)
                    }
                }

                // Hint for known call recorder paths
                Text(
                    "Chemins communs : /Recordings/Call · /MIUI/sound_recorder/call_rec · /PhoneRecord",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(url, user, pass, folder)
                    onDismiss()
                },
                modifier = Modifier.testTag("save_settings_button")
            ) { Text("Enregistrer") }
        },
        dismissButton = {
            TextButton(
                onClick  = onDismiss,
                modifier = Modifier.testTag("cancel_settings_button")
            ) { Text("Annuler") }
        }
    )
}

/**
 * Resolves an SAF Document Tree URI to an absolute physical path.
 * Works for primary storage and external SD cards.
 */
private fun getPhysicalPathFromUri(context: Context, uri: Uri): String? {
    return try {
        if (!DocumentsContract.isTreeUri(uri)) return null
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val parts  = docId.split(":")
        if (parts.size < 2) return null

        val storageType  = parts[0].lowercase()
        val relativePath = parts[1]

        if (storageType == "primary") {
            return File(Environment.getExternalStorageDirectory(), relativePath).absolutePath
        }

        // External SD card — scan app's external dirs
        context.getExternalFilesDirs(null).filterNotNull().forEach { extDir ->
            val path = extDir.absolutePath
            val idx  = path.indexOf("/Android/")
            if (idx >= 0) {
                val root = path.substring(0, idx)
                if (root.contains(storageType, ignoreCase = true)) {
                    return File(root, relativePath).absolutePath
                }
            }
        }
        // Generic fallback
        "/storage/$storageType/$relativePath"
    } catch (_: Exception) {
        null
    }
}
