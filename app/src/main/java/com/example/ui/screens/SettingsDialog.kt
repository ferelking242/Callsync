package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    var url by remember { mutableStateOf(initialUrl) }
    var user by remember { mutableStateOf(initialUser) }
    var pass by remember { mutableStateOf(initialPass) }
    var folder by remember { mutableStateOf(initialFolder) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paramètres du Serveur") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL du Serveur") },
                    modifier = Modifier.fillMaxWidth().testTag("server_url_input"),
                    singleLine = true
                )

                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("Nom d'utilisateur") },
                    modifier = Modifier.fillMaxWidth().testTag("username_input"),
                    singleLine = true
                )

                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Mot de passe") },
                    modifier = Modifier.fillMaxWidth().testTag("password_input"),
                    singleLine = true
                )

                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    label = { Text("Dossier à surveiller") },
                    modifier = Modifier.fillMaxWidth().testTag("monitor_folder_input"),
                    singleLine = true
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
            ) {
                Text("Enregistrer")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("cancel_settings_button")
            ) {
                Text("Annuler")
            }
        }
    )
}
