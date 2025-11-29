package com.hci.imudata_handedness


import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.BorderStroke
import android.Manifest
import android.content.*
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hci.imudata_handedness.ui.theme.IMUData_HandednessTheme
import android.net.Uri
import androidx.compose.material3.HorizontalDivider
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember


class MainActivity : ComponentActivity() {



    @Composable
    fun AppNavigation() {
        var currentScreen by remember { mutableStateOf("main") }

        when (currentScreen) {
            "main" -> MainScreen(
                onSeeRecordings = { currentScreen = "recordings" }
            )
            "recordings" -> RecordingsScreen(
                onBack = { currentScreen = "main" }
            )
        }
    }


    enum class Mode { LEFT, RIGHT, BOTH }

    private val prefs by lazy { getSharedPreferences("imu_prefs", Context.MODE_PRIVATE) }

    //permissions
    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val granted = results.entries.all { it.value }
            if (!granted) {
                Toast.makeText(this, "Some permissions were denied — background detection may be limited.", Toast.LENGTH_LONG).show()
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        setContent {
            IMUData_HandednessTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavigation()
                }
            }
        }
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (perms.isNotEmpty()) requestPermissionsLauncher.launch(perms.toTypedArray())
    }

    @Composable
    fun MainScreen(onSeeRecordings: () -> Unit) {
        val context = LocalContext.current
        var mode by remember { mutableStateOf(Mode.valueOf(prefs.getString("last_mode", Mode.BOTH.name)!!)) }

        LaunchedEffect(mode) {
            prefs.edit().putString("last_mode", mode.name).apply()
        }
        var recording by remember { mutableStateOf(prefs.getBoolean("is_recording", false)) }
        var showExportDialog by remember { mutableStateOf(false) }
        val snackbarHostState = remember { SnackbarHostState() }
        val coroutineScope = rememberCoroutineScope()

        //receive start/stop broadcasts
        DisposableEffect(Unit) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    when (intent?.action) {
                        "com.hci.imudata_handedness.RECORDING_STARTED" -> {
                            recording = true
                            prefs.edit().putBoolean("is_recording", true).apply()
                            Toast.makeText(context, "Recording started", Toast.LENGTH_SHORT).show()
                        }
                        "com.hci.imudata_handedness.RECORDING_STOPPED" -> {
                            recording = false
                            prefs.edit().putBoolean("is_recording", false).apply()
                            Toast.makeText(context, "Recording stopped", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction("com.hci.imudata_handedness.RECORDING_STARTED")
                addAction("com.hci.imudata_handedness.RECORDING_STOPPED")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            }

            onDispose { context.unregisterReceiver(receiver) }
        }

        //sync with prefs on reopen
        LaunchedEffect(Unit) {
            recording = prefs.getBoolean("is_recording", false)
        }

        Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Spacer(modifier = Modifier.height(18.dp))
                Text("LaySwitch — IMU Recorder", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(12.dp))
                Text("Select mode (disabled while recording):", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.Center) {
                    Mode.values().forEach { m ->
                        val isSelected = (m == mode)
                        OutlinedButton(
                            onClick = { if (!recording) mode = m },
                            enabled = !recording,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier
                                .padding(horizontal = 6.dp)
                                .height(42.dp)
                        ) { Text(m.name) }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))


                Button(
                    onClick = {
                        if (!recording) startRecordingService(context, mode)
                        else stopRecordingService(context)
                    },
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (recording)
                            Color(0xFFCB3E3E) //material red 700
                        else
                            Color(0xFF4F8E38),//mterial green 700
                        contentColor = Color.White
                    ),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text(
                        text = if (recording) "Stop Recording" else "Start Recording",
                        color = Color.White
                    )
                }


                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Status: ${if (recording) "Recording (${mode.name})" else "Idle (${mode.name})"}",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .padding(vertical = 12.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )

                OutlinedButton(
                    onClick = { showExportDialog = true },
                    enabled = !recording,
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(50.dp)
                ) { Text("Export CSVs") }

                Spacer(modifier = Modifier.height(5.dp))
                OutlinedButton(
                    onClick = onSeeRecordings,
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(50.dp)
                ) {
                    Text("See Recordings")
                }

                Spacer(modifier = Modifier.height(16.dp))
                Card {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Note:", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Recording stops automatically when locked, inactive, or manually stopped.")
                        Text("Use 'Export CSVs' to share or back up data.")
                    }
                }


                if (showExportDialog) {
                    AlertDialog(
                        onDismissRequest = { showExportDialog = false },
                        title = { Text("Export Data") },
                        text = { Text("Do you want to export all recorded CSV files?") },
                        confirmButton = {
                            TextButton(onClick = {
                                showExportDialog = false
                                coroutineScope.launch {
                                    val success = exportCsvFiles()
                                    snackbarHostState.showSnackbar(
                                        if (success) "Export completed successfully!" else "Export failed."
                                    )
                                }
                            }) { Text("Export") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showExportDialog = false }) { Text("Cancel") }
                        }
                    )
                }
            }
        }
    }



    //service communication
    private fun startRecordingService(context: Context, mode: Mode) {
        val intent = Intent(context, com.hci.imudata_handedness.imu.RecordingService::class.java).apply {
            action = com.hci.imudata_handedness.imu.RecordingService.ACTION_START
            putExtra(com.hci.imudata_handedness.imu.RecordingService.EXTRA_MODE, mode.name)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    private fun stopRecordingService(context: Context) {
        val intent = Intent(context, com.hci.imudata_handedness.imu.RecordingService::class.java).apply {
            action = com.hci.imudata_handedness.imu.RecordingService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun exportCsvFiles(): Boolean {
        val exportDir = getExternalFilesDir("imu_data") ?: return false
        val csvFiles = exportDir.listFiles { f -> f.extension == "csv" } ?: return false
        if (csvFiles.isEmpty()) {
            Toast.makeText(this, "No CSV files found to export", Toast.LENGTH_SHORT).show()
            return false
        }

        val zipFile = File(exportDir, "imu_data_export_${System.currentTimeMillis()}.zip")
        return try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                csvFiles.forEach { file ->
                    FileInputStream(file).use { fis ->
                        val entry = ZipEntry(file.name)
                        zos.putNextEntry(entry)
                        fis.copyTo(zos)
                        zos.closeEntry()
                    }
                }
            }

            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", zipFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share IMU Data"))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to export data", Toast.LENGTH_SHORT).show()
            false
        }
    }
}


