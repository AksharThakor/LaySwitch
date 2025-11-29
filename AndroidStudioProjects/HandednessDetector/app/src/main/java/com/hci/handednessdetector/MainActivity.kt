package com.hci.handednessdetector


import android.graphics.Rect
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp


object MainScreenState {
    var onResult: ((String, FloatArray) -> Unit)? = null
    fun update(label: String, probs: FloatArray) {
        onResult?.invoke(label, probs)
    }
}

class MainActivity : ComponentActivity() {

    private var imuCollector: ImuCollector? = null
    private var interpreter: HandednessInterpreter? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load interpreter
        interpreter = HandednessInterpreter(this, "imu_cnn_model.tflite")

        setContent {
            MaterialTheme {
                MainScreen(
                    onKeyboardShown = {
                        // Start IMU
                        if (imuCollector == null) {
                            imuCollector = ImuCollector(this, interpreter!!) { label, probs ->
                                // post result to a static/shared variable or use a simple global state updater
                                MainScreenState.update(label, probs)
                            }
                        }
                        imuCollector?.start()
                    },
                    onKeyboardHidden = {
                        imuCollector?.stop()
                    },
                    getInterpreter = { interpreter }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        imuCollector?.stop()
        interpreter?.close()
    }
}

@Composable
fun MainScreen(
    onKeyboardShown: () -> Unit,
    onKeyboardHidden: () -> Unit,
    getInterpreter: () -> HandednessInterpreter?
) {
    val view = LocalView.current
    val rootView = view.rootView

    var predictedLabel by remember { mutableStateOf("—") }
    var confidences by remember { mutableStateOf<FloatArray?>(null) }
    var inputText by remember { mutableStateOf(TextFieldValue("")) }

    val resultCallback = rememberUpdatedState(newValue = { label: String, probs: FloatArray ->
        predictedLabel = label
        confidences = probs
    })

    LaunchedEffect(Unit) {
        MainScreenState.onResult = { label, probs -> resultCallback.value(label, probs) }
    }

    // Keyboard visibility detection
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val rect = Rect()
            rootView.getWindowVisibleDisplayFrame(rect)
            val screenHeight = rootView.height
            val keypadHeight = screenHeight - rect.bottom
            val isKeyboardNowVisible = keypadHeight > screenHeight * 0.15

            if (isKeyboardNowVisible) onKeyboardShown() else {
        onKeyboardHidden()
        // Reset prediction when exit from keyboard
        predictedLabel = "—"
        confidences = null}
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- Title ---
        Text(
            "Handedness Detection",
            style = MaterialTheme.typography.headlineMedium,
            color = Color(0xFF1976D2)
        )
        Spacer(Modifier.height(20.dp))

        // Input + Prediction
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Type here…") },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                singleLine = true,
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedContainerColor =Color(0xFFF5F5F5),
                    focusedTextColor = Color(0xFF1976D2),
                    unfocusedTextColor = Color(0xFFBDBDBD),
                    focusedPlaceholderColor  = Color.Gray,
                    focusedSupportingTextColor = Color.Black
                ),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.width(12.dp))

            // Prediction box
            val boxColor = when (predictedLabel.uppercase()) {
                "LEFT" -> Color(0xFFEF5350)
                "RIGHT" -> Color(0xFF66BB6A)
                "BOTH" -> Color(0xFF42A5F5)
                else -> Color(0xFFEEEEEE)
            }

            Column(
                modifier = Modifier
                    .width(140.dp)
                    .height(56.dp)
                    .background(boxColor.copy(alpha = 0.15f), shape = RoundedCornerShape(12.dp))
                    .border(
                        width = 1.dp,
                        color = boxColor,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    predictedLabel,
                    style = MaterialTheme.typography.titleLarge,
                    color = boxColor
                )
                confidences?.let { conf ->
                    Text(
                        String.format("%.2f  %.2f  %.2f", conf[0], conf[1], conf[2]),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.DarkGray
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Info
        Text(
            "Prediction updates every 1s using overlapped(50%) buffered 2sec windows",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}
