package com.autoswipe.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.autoswipe.app.ui.theme.AutoSwipeTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AutoSwipeTheme {
                var showSettings by remember { mutableStateOf(false) }

                if (showSettings) {
                    SettingsScreen(onBack = { showSettings = false })
                } else {
                    AutoSwipeScreen(onOpenSettings = { showSettings = true })
                }
            }
        }
    }

    private fun testSwipe() {

        // App ko background mein bhejo
        moveTaskToBack(true)

        // 1 second wait
        Handler(Looper.getMainLooper()).postDelayed({

            // Accessibility Service se swipe
            AutoSwipeAccessibilityService.instance
                ?.performSwipeUp()

        }, 1000)
    }

    @Composable
    fun AutoSwipeScreen(onOpenSettings: () -> Unit) {

        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current

        var isServiceEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
        var isAutoSwipeEnabled by remember {
            mutableStateOf(AutoSwipeAccessibilityService.isAutoSwipeEnabled(context))
        }

        // Settings se wapas aane par service status refresh karo
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    isServiceEnabled = isAccessibilityServiceEnabled(context)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            Text(
                text = "AutoSwipe",
                fontSize = 32.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {

                    Text(
                        text = if (isServiceEnabled) {
                            "Accessibility Service: ON"
                        } else {
                            "Accessibility Service: OFF"
                        },
                        color = if (isServiceEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (!isServiceEnabled) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { openAccessibilitySettings(context) }
                        ) {
                            Text("Enable in Settings")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto-Swipe")

                    Switch(
                        checked = isAutoSwipeEnabled,
                        enabled = isServiceEnabled,
                        onCheckedChange = { enabled ->
                            isAutoSwipeEnabled = enabled
                            AutoSwipeAccessibilityService.setAutoSwipeEnabled(context, enabled)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenSettings
            ) {
                Text("Settings")
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                enabled = isServiceEnabled,
                onClick = { testSwipe() }
            ) {
                Text("TEST SWIPE ↑")
            }
        }
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit) {

    val context = LocalContext.current

    var targetPackage by remember {
        mutableStateOf(AutoSwipeAccessibilityService.getTargetPackage(context))
    }
    var nearEndRatio by remember {
        mutableStateOf(AutoSwipeAccessibilityService.getNearEndRatio(context))
    }
    var resetRatio by remember {
        mutableStateOf(AutoSwipeAccessibilityService.getResetRatio(context))
    }
    var cooldownText by remember {
        mutableStateOf(AutoSwipeAccessibilityService.getSwipeCooldownMs(context).toString())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {

        Text(text = "Settings", fontSize = 28.sp)

        Spacer(modifier = Modifier.height(24.dp))

        Text(text = "Target app package(s)")
        Text(
            text = "Comma-separated to watch multiple apps at once",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = targetPackage,
            onValueChange = {
                targetPackage = it
                if (it.isNotBlank()) {
                    AutoSwipeAccessibilityService.setTargetPackage(context, it)
                }
            },
            singleLine = true
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(text = "Near-end ratio: ${"%.2f".format(nearEndRatio)}")
        Text(
            text = "How far into the video counts as \"about to end\"",
            style = MaterialTheme.typography.bodySmall
        )
        Slider(
            value = nearEndRatio,
            valueRange = 0.5f..0.99f,
            onValueChange = { newValue ->
                nearEndRatio = newValue
                // Reset ratio kabhi bhi near-end ratio se bada nahi ho sakta
                if (resetRatio >= newValue) {
                    resetRatio = (newValue - 0.05f).coerceAtLeast(0f)
                    AutoSwipeAccessibilityService.setResetRatio(context, resetRatio)
                }
                AutoSwipeAccessibilityService.setNearEndRatio(context, newValue)
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(text = "Reset ratio: ${"%.2f".format(resetRatio)}")
        Text(
            text = "How low progress must drop back to, to confirm a loop",
            style = MaterialTheme.typography.bodySmall
        )
        Slider(
            value = resetRatio,
            valueRange = 0f..(nearEndRatio - 0.05f).coerceAtLeast(0f),
            onValueChange = { newValue ->
                resetRatio = newValue
                AutoSwipeAccessibilityService.setResetRatio(context, newValue)
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(text = "Swipe cooldown (ms)")
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = cooldownText,
            onValueChange = { text ->
                cooldownText = text
                val parsed = text.toLongOrNull()
                if (parsed != null && parsed > 0) {
                    AutoSwipeAccessibilityService.setSwipeCooldownMs(context, parsed)
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(
                onClick = {
                    AutoSwipeAccessibilityService.resetToDefaults(context)
                    targetPackage = AutoSwipeAccessibilityService.getTargetPackage(context)
                    nearEndRatio = AutoSwipeAccessibilityService.getNearEndRatio(context)
                    resetRatio = AutoSwipeAccessibilityService.getResetRatio(context)
                    cooldownText =
                        AutoSwipeAccessibilityService.getSwipeCooldownMs(context).toString()
                }
            ) {
                Text("Reset to defaults")
            }

            Button(onClick = onBack) {
                Text("Back")
            }
        }
    }
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {

    val expectedComponentName = ComponentName(context, AutoSwipeAccessibilityService::class.java)

    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)

    while (colonSplitter.hasNext()) {
        val enabledComponentName = ComponentName.unflattenFromString(colonSplitter.next())
        if (enabledComponentName == expectedComponentName) {
            return true
        }
    }

    return false
}

private fun openAccessibilitySettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}
