package com.example.ui.screens

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Web
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
    onSave: (url: String, user: String, pass: String, folder: String) -> Unit
) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(initialUrl) }
    var user by remember { mutableStateOf(initialUser) }
    var pass by remember { mutableStateOf(initialPass) }
    var folder by remember { mutableStateOf(initialFolder) }

    // System File/Folder Picker launcher
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val resolvedPath = getPathFromUri(context, uri)
            if (resolvedPath != null) {
                folder = resolvedPath
                Toast.makeText(context, "Dossier physique résolu: $resolvedPath", Toast.LENGTH_LONG).show()
            } else {
                folder = uri.toString()
                Toast.makeText(context, "Dossier configuré via Uri de stockage.", Toast.LENGTH_LONG).show()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Paramètres de Connexion",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Server URL Input
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Adresse URL du Serveur Go") },
                    leadingIcon = { Icon(Icons.Default.Web, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().testTag("server_url_input"),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                )

                // Username Input
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("Nom d'utilisateur") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().testTag("username_input"),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                )

                // Password Input
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Mot de passe du serveur") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().testTag("password_input"),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                )

                // Folder Monitor with explicit system SAF Browse trigger
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Dossier surveillé permanent",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = folder,
                            onValueChange = { folder = it },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("monitor_folder_input"),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        )

                        Button(
                            onClick = { folderLauncher.launch(null) },
                            modifier = Modifier
                                .height(56.dp)
                                .testTag("browse_folder_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            )
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "Parcourir")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(url, user, pass, folder)
                    onDismiss()
                },
                modifier = Modifier.testTag("save_settings_button"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Enregistrer")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("cancel_settings_button")
            ) {
                Text("Annuler", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

/**
 * Resolves a Storage Access Framework (SAF) Document Tree Uri to a physical absolute path.
 */
private fun getPathFromUri(context: Context, uri: Uri): String? {
    try {
        if (DocumentsContract.isTreeUri(uri)) {
            val documentId = DocumentsContract.getTreeDocumentId(uri)
            val split = documentId.split(":")
            if (split.size >= 2) {
                val type = split[0]
                val relativePath = split[1]
                if ("primary" == type.lowercase()) {
                    return File(Environment.getExternalStorageDirectory(), relativePath).absolutePath
                } else {
                    // Search through external SD cards paths if configured
                    val extDirs = context.getExternalFilesDirs(null)
                    for (extDir in extDirs) {
                        if (extDir != null) {
                            val path = extDir.absolutePath
                            val index = path.indexOf("/Android/")
                            if (index >= 0) {
                                val root = path.substring(0, index)
                                if (root.contains(type)) {
                                    return File(root, relativePath).absolutePath
                                }
                            }
                        }
                    }
                    // Generic fallback
                    return "/storage/$type/$relativePath"
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}
