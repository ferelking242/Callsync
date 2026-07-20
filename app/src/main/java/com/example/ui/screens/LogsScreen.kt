package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.LogEntry
import com.example.ui.viewmodel.CallSyncViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(viewModel: CallSyncViewModel) {
    val logs by viewModel.logs.collectAsState()
    var filterQuery by remember { mutableStateOf("") }

    val filteredLogs = remember(logs, filterQuery) {
        if (filterQuery.isEmpty()) {
            logs
        } else {
            logs.filter {
                it.tag.contains(filterQuery, ignoreCase = true) ||
                it.message.contains(filterQuery, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Search & Control Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = filterQuery,
                onValueChange = { filterQuery = it },
                placeholder = { Text("Filtrer les logs...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("logs_filter_input"),
                singleLine = true
            )

            Button(
                onClick = { viewModel.clearAllLogs() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("clear_logs_button")
            ) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "Vider")
                Spacer(modifier = Modifier.width(4.dp))
                Text("Vider")
            }
        }

        // Logs Terminal View
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = Color.Black)
        ) {
            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Aucun log enregistré.",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .testTag("logs_list"),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredLogs) { logEntry ->
                        LogLineItem(logEntry)
                    }
                }
            }
        }
    }
}

@Composable
fun LogLineItem(log: LogEntry) {
    val sdf = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val formattedTime = remember(log.timestamp) { sdf.format(Date(log.timestamp)) }

    val textColor = when {
        log.isError -> Color(0xFFE57373) // Light Red for errors
        log.tag == "Uploader" -> Color(0xFF81C784) // Light Green for uploads
        log.tag == "Viewer" -> Color(0xFF64B5F6) // Light Blue for streams
        log.tag == "Service" -> Color(0xFFFFB74D) // Orange for service logs
        else -> Color(0xFFE0E0E0) // Gray/White for others
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = formattedTime,
            color = Color.Gray,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )

        Text(
            text = "[${log.tag}]",
            color = textColor.copy(alpha = 0.8f),
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )

        Text(
            text = log.message,
            color = textColor,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}
