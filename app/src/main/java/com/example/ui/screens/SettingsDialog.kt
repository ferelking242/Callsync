package com.example.ui.screens

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
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                folder = uri.toString()
                Toast.makeText(context, "Dossier SAF configuré.", Toast.LENGTH_LONG).show()
            } catch (error: SecurityException) {
                Toast.makeText(
                    context,
                    "Impossible de conserver l’autorisation du dossier : ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
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
                    onValueChange = {},
                    readOnly      = true,
                    label         = { Text("Dossier sélectionné") },
                    leadingIcon   = { Icon(Icons.Default.Folder, null) },
                    modifier      = Modifier.fillMaxWidth().testTag("monitor_folder_input"),
                    singleLine    = true
                )

                OutlinedButton(
                    onClick  = { folderLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth().testTag("browse_folder_button"),
                    shape    = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Choisir le dossier des enregistrements")
                }

                Text(
                    "Utilisez ce bouton pour accorder l’accès au dossier et à tous ses sous-dossiers. " +
                        "Ne saisissez pas un chemin /storage/...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )

                HorizontalDivider()
                Text(
                    "Les nouveaux fichiers audio sont envoyés automatiquement dès qu’ils sont stables.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
