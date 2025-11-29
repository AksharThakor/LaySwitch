package com.hci.imudata_handedness

import androidx.activity.compose.BackHandler
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import android.widget.Toast
import com.hci.imudata_handedness.imu.RecordingServiceHolder


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var files by remember { mutableStateOf(loadIMUFiles(context)) }

    val refreshState = rememberPullToRefreshState()
    var refreshing by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    //pull to refresh
    LaunchedEffect(refreshing) {
        if (refreshing) {
            delay(600) //simulate refresh delay
            files = loadIMUFiles(context)
            refreshing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved Recordings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            state = refreshState,
            isRefreshing = refreshing,
            onRefresh = { refreshing = true },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (files.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Text("No recordings found.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(files) { file ->
                        RecordingItem(
                            file = file,
                            onDelete = {
                                val activeFile = RecordingServiceHolder.currentRecordingFile
                                if (activeFile != null && activeFile.absolutePath == file.absolutePath) {
                                    //show a toast if trying to delete a file being written into by recorder
                                    Toast.makeText(
                                        context,
                                        "Cannot delete — recording in progress.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    file.delete()
                                    files = loadIMUFiles(context)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingItem(file: File, onDelete: () -> Unit) {
    val name = file.name
    val base = name.removePrefix("IMU_").removeSuffix(".csv")
    val parts = base.split("_")

    val handedness = parts.getOrNull(0) ?: "Unknown"

    //format date + time correctly
    val datePart = parts.getOrNull(1)
    val timePart = parts.getOrNull(2)
    val timestamp = if (datePart != null && timePart != null) {
        try {
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val date = sdf.parse("${datePart}_${timePart}")
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(date!!)
        } catch (_: Exception) {
            "$datePart $timePart"
        }
    } else "N/A"

    Card(
        Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text("Hand: $handedness", style = MaterialTheme.typography.bodyLarge)
                Text("Time: $timestamp", style = MaterialTheme.typography.bodyMedium)
                Text("Size: ${file.length() / 1024} KB", style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

private fun loadIMUFiles(context: Context): List<File> {
    val dir = File(context.getExternalFilesDir(null), "imu_data")
    return dir.listFiles { f -> f.extension == "csv" }
        ?.sortedByDescending { it.lastModified() }
        //force new object creation to trigger recomposition
        ?.map { File(it.absolutePath) }
        ?: emptyList()
}

