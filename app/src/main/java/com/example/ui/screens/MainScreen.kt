package com.example.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.example.ui.viewmodel.CallSyncViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: CallSyncViewModel) {
    var isSettingsOpen by remember { mutableStateOf(false) }

    val serverUrl     by viewModel.serverUrl.collectAsState()
    val username      by viewModel.username.collectAsState()
    val password      by viewModel.password.collectAsState()
    val monitorFolder by viewModel.monitorFolder.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "CallSync",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    IconButton(
                        onClick = { isSettingsOpen = true },
                        modifier = Modifier.testTag("settings_top_button")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Paramètres")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        UploaderScreen(
            viewModel = viewModel,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }

    if (isSettingsOpen) {
        SettingsDialog(
            initialUrl    = serverUrl,
            initialUser   = username,
            initialPass   = password,
            initialFolder = monitorFolder,
            onDismiss     = { isSettingsOpen = false },
            onSave        = { url, user, pass, folder ->
                viewModel.saveSettings(url, user, pass, folder)
            },
            onAutoDetect = { viewModel.autoDetectFolder() }
        )
    }
}
