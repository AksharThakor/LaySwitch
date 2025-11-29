package com.hci.imudata_handedness.imu

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.hci.imudata_handedness.MainActivity
import com.hci.imudata_handedness.R
import timber.log.Timber
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import androidx.core.content.edit

class RecordingService : Service(), SensorEventListener {

    companion object {
        const val CHANNEL_ID = "imu_recording_channel"
        const val NOTIFICATION_ID = 42

        //start stop actions
        const val ACTION_START = "com.hci.imudata_handedness.START_RECORDING"
        const val ACTION_STOP = "com.hci.imudata_handedness.STOP_RECORDING"
        const val EXTRA_MODE = "extra_mode"

        //broadcasts the service
        const val ACTION_RECORDING_STARTED = "com.hci.imudata_handedness.RECORDING_STARTED"
        const val ACTION_RECORDING_STOPPED = "com.hci.imudata_handedness.RECORDING_STOPPED"

        //limits
        const val MAX_FILE_SIZE_BYTES = 50L * 1024L * 1024L // 50 MB
        const val MAX_SESSION_MS = 10 * 60 * 1000L // 10 minutes
    }

    private lateinit var sensorManager: SensorManager
    private var accel: Sensor? = null
    private var gyro: Sensor? = null

    private var handedness: String = "BOTH"
    private val isRecording = AtomicBoolean(false)
    private var startTime: Long = 0L
    private var outputFile: File? = null
    private var writer: BufferedWriter? = null

    private lateinit var activityRecognitionClient: ActivityRecognitionClient
    @Volatile
    var isStill = false

    private val sampleRateHz = 60
    private val samplePeriodUs = (1_000_000 / sampleRateHz)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        activityRecognitionClient = ActivityRecognition.getClient(this)
        createNotificationChannel()
        RecordingServiceHolder.serviceInstance = this
        Timber.tag("RecordingService").d("onCreate")
    }

    @RequiresPermission(Manifest.permission.ACTIVITY_RECOGNITION)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent.getStringExtra(EXTRA_MODE) ?: "BOTH")
            ACTION_STOP -> {
                Timber.tag("RecordingService").d("ACTION_STOP received")
                stopRecording()
            }
            else -> Timber.tag("RecordingService")
                .d("onStartCommand: unknown action ${intent?.action}")
        }
        //keep running we manage stop explicitly
        return START_STICKY
    }

    //allow external callers
    @RequiresPermission(Manifest.permission.ACTIVITY_RECOGNITION)
    fun requestStopFromExternal() {
        stopRecording()
    }

    @RequiresPermission(Manifest.permission.ACTIVITY_RECOGNITION)
    private fun startRecording(mode: String) {
        if (isRecording.get()) return

        handedness = mode
        startTime = System.currentTimeMillis()

        //start foreground with notification
        startForeground(NOTIFICATION_ID, buildNotification("Recording ($mode)"))

        //create file and a writer obj
        outputFile = createNewFile()
        writer = BufferedWriter(FileWriter(outputFile!!, true)).apply {
            write("timestamp,handedness,ax,ay,az,gx,gy,gz\n")
            flush()
        }

        //register sample
        accel?.also { sensorManager.registerListener(this, it, samplePeriodUs) }
        gyro?.also { sensorManager.registerListener(this, it, samplePeriodUs) }

        //register activity transitions
        try {
            subscribeActivityTransitions()
        } catch (ex: Exception) {
            Timber.tag("RecordingService").w("subscribeActivityTransitions failed: ${ex.message}")
        }

        isRecording.set(true)

        //Persist state for UI selected button
        getSharedPreferences("imu_prefs", Context.MODE_PRIVATE)
            .edit { putBoolean("is_recording", true) }

        //start broadcast
        sendBroadcast(Intent(ACTION_RECORDING_STARTED))
        Timber.tag("RecordingService").d("Broadcast: RECORDING_STARTED")

        //Autostop after max sessions
        Handler(Looper.getMainLooper()).postDelayed(@RequiresPermission(Manifest.permission.ACTIVITY_RECOGNITION) {
            if (isRecording.get()) {
                Timber.tag("RecordingService").d("Watchdog triggered - stopping recording")
                stopRecording()
            }
        }, MAX_SESSION_MS)

        RecordingServiceHolder.currentRecordingFile = outputFile
    }

    //stop recording public so reciever can call it via serviceInstance
    @RequiresPermission(Manifest.permission.ACTIVITY_RECOGNITION)
    fun stopRecording() {
        if (!isRecording.getAndSet(false)) {
            Timber.tag("RecordingService").d("stopRecording() called but not recording")
            return
        }

        //unreg sensor and activity
        try { sensorManager.unregisterListener(this) } catch (_: Exception) {}
        try { unsubscribeActivityTransitions() } catch (_: Exception) {}

        //flush and close writer
        try {
            writer?.flush()
            writer?.close()
        } catch (e: IOException) {
            Timber.tag("RecordingService").w("Error closing writer: ${e.message}")
        }

        //persist state
        getSharedPreferences("imu_prefs", Context.MODE_PRIVATE)
            .edit { putBoolean("is_recording", false) }

        //send broadcast before stopping so UI updates
        sendBroadcast(Intent(ACTION_RECORDING_STOPPED))
        Timber.tag("RecordingService").d("Broadcast: RECORDING_STOPPED")

        //remove foreground and stop service
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        RecordingServiceHolder.currentRecordingFile = null
        stopSelf()
        Timber.tag("RecordingService").d("stopRecording completed - service stopped")
    }

    private fun createNewFile(): File {
        val dir = File(getExternalFilesDir(null), "imu_data")
        if (!dir.exists()) dir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "IMU_${handedness}_$timestamp.csv")
    }

    private fun buildNotification(status: String): Notification {
        //reopen app when notification tapped
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpenIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        //sends intent for this service to be stopped from notification
        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("IMU Data Recording")
            .setContentText(status)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingOpenIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "IMU Recording", NotificationManager.IMPORTANCE_LOW)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    //sensor state
    private var ax = 0f; private var ay = 0f; private var az = 0f
    private var gx = 0f; private var gy = 0f; private var gz = 0f

    @RequiresPermission(Manifest.permission.ACTIVITY_RECOGNITION)
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isRecording.get()) return

        //If device locked we stop
        if (isStill || isDeviceLocked()) {
            Timber.tag("RecordingService").d("Device locked or STILL - stopping")
            stopRecording()
            return
        }

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                ax = event.values[0]; ay = event.values[1]; az = event.values[2]
            }
            Sensor.TYPE_GYROSCOPE -> {
                gx = event.values[0]; gy = event.values[1]; gz = event.values[2]

                val ts = System.currentTimeMillis()
                val line = "$ts,$handedness,$ax,$ay,$az,$gx,$gy,$gz\n"

                try {
                    writer?.apply {
                        write(line)
                        flush()
                    }

                    //rotate file if too large
                    if ((outputFile?.length() ?: 0L) > MAX_FILE_SIZE_BYTES) {
                        try { writer?.close() } catch (_: Exception) {}
                        outputFile = createNewFile()
                        writer = BufferedWriter(FileWriter(outputFile!!, true)).apply {
                            write("timestamp,handedness,ax,ay,az,gx,gy,gz\n"); flush()
                        }
                    }
                } catch (e: IOException) {
                    Timber.tag("RecordingService").e("Error writing IMU data: ${e.message}")
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    //activity recognition
    @RequiresPermission(Manifest.permission.ACTIVITY_RECOGNITION)
    private fun subscribeActivityTransitions() {
        val transitions = listOf(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.STILL)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.STILL)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build()
        )

        val request = ActivityTransitionRequest(transitions)

        val intent = Intent(this, ActivityTransitionReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(this, 12345, intent, flags)

        activityRecognitionClient.requestActivityTransitionUpdates(request, pi)
        Timber.tag("RecordingService").d("ActivityTransition subscription requested")
    }

    @RequiresPermission(Manifest.permission.ACTIVITY_RECOGNITION)
    private fun unsubscribeActivityTransitions() {
        val intent = Intent(this, ActivityTransitionReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(this, 12345, intent, flags)
        try {
            activityRecognitionClient.removeActivityTransitionUpdates(pi)
            Timber.tag("RecordingService").d("ActivityTransition subscription removed")
        } catch (ex: Exception) {
            Timber.tag("RecordingService").w("Failed to remove activity transitions: ${ex.message}")
        }
    }

    private fun isDeviceLocked(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return km.isDeviceLocked
    }

    @RequiresPermission(Manifest.permission.ACTIVITY_RECOGNITION)
    override fun onDestroy() {
        //safe stop
        try { stopRecording() } catch (_: Exception) {}
        RecordingServiceHolder.serviceInstance = null
        super.onDestroy()
    }
}


class ActivityTransitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        try {
            if (ActivityTransitionResult.hasResult(intent)) {
                val result = ActivityTransitionResult.extractResult(intent)!!
                result.transitionEvents.forEach { ev ->
                    Timber.tag("ActivityTransitionReceiver")
                        .d("ev: activity=${ev.activityType} trans=${ev.transitionType}")
                    if (ev.activityType == DetectedActivity.STILL) {
                        //enter if device still exit if device again active
                        val service = RecordingServiceHolder.serviceInstance
                        if (service != null) {
                            if (ev.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                                Timber.tag("ActivityTransitionReceiver")
                                    .d("STILL ENTER -> asking service to stop")
                                //mark still, and ask service to stop
                                service.isStill = true
                                service.requestStopFromExternal()
                            } else if (ev.transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT) {
                                Timber.tag("ActivityTransitionReceiver")
                                    .d("STILL EXIT -> clearing still flag")
                                service.isStill = false
                            }
                        } else {
                            Timber.tag("ActivityTransitionReceiver")
                                .d("serviceInstance null - cannot notify")
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            Timber.tag("ActivityTransitionReceiver").w("onReceive error: ${ex.message}")
        }
    }
}

object RecordingServiceHolder {
    @Volatile var serviceInstance: RecordingService? = null
    var currentRecordingFile: File? = null
}
