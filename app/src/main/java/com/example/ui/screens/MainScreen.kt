package com.example.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.example.ui.viewmodel.CallSyncViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: CallSyncViewModel) {
    var selectedTab by remember { mutableStateOf(0) }
    var isSettingsOpen by remember { mutableStateOf(false) }

    val serverUrl by viewModel.serverUrl.collectAsState()
    val username by viewModel.username.collectAsState()
    val password by viewModel.password.collectAsState()
    val monitorFolder by viewModel.monitorFolder.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CallSync") },
                actions = {
                    IconButton(
                        onClick = { isSettingsOpen = true },
                        modifier = Modifier.testTag("settings_top_button")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Paramètres")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.testTag("bottom_nav_bar")
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Upload, contentDescription = "Uploader") },
                    label = { Text("Uploader") },
                    modifier = Modifier.testTag("tab_uploader")
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = "Viewer") },
                    label = { Text("Viewer") },
                    modifier = Modifier.testTag("tab_viewer")
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.List, contentDescription = "Logs") },
                    label = { Text("Logs") },
                    modifier = Modifier.testTag("tab_logs")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> UploaderScreen(
                    viewModel = viewModel,
                    onNavigateToLogs = { selectedTab = 2 }
                )
                1 -> ViewerScreen(
                    viewModel = viewModel
                )
                2 -> LogsScreen(
                    viewModel = viewModel
                )
            }
        }
    }

    if (isSettingsOpen) {
        SettingsDialog(
            initialUrl = serverUrl,
            initialUser = username,
            initialPass = password,
            initialFolder = monitorFolder,
            onDismiss = { isSettingsOpen = false },
            onSave = { url, user, pass, folder ->
                viewModel.saveSettings(url, user, pass, folder)
            }
        )
    }
}
