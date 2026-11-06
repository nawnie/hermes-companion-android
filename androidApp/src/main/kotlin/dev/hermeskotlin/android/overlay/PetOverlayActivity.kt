package dev.hermeskotlin.android.overlay

import dev.hermeskotlin.ui.pet.PetOverlayArtwork
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/** Entry point for appearance and the Android display-over-other-apps permission. */
class PetOverlayActivity : ComponentActivity() {
    private lateinit var prefs: OverlayPrefs
    private var opacity by mutableFloatStateOf(.75f)
    private var height by mutableFloatStateOf(150f)
    private var position by mutableFloatStateOf(.85f)
    private var running by mutableStateOf(false)
    private var artworkName by mutableStateOf<String?>(null)
    private var message by mutableStateOf<String?>(null)
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) startOverlay()
        else message = "Allow display over other apps to show your pet."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = OverlayPrefs(this)
        opacity = prefs.opacity.coerceAtMost(.8f)
        height = prefs.heightDp.toFloat()
        position = prefs.position
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFCCB8FF), background = Color(0xFF17131F), surface = Color(0xFF17131F))) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Floating pet", style = MaterialTheme.typography.headlineMedium)
                    Text(artworkName?.let { "$it will appear over your other apps. Tap the Hermes sign for chat or hold it to return here. Taps on the pet pass through." }
                        ?: "Choose a pet in your connected profile first, then return here.")
                    Text("Opacity: ${(opacity * 100).toInt()}%")
                    Slider(value = opacity, onValueChange = { opacity = it; prefs.opacity = it },
                        onValueChangeFinished = ::refreshOverlay, valueRange = .2f..0.8f)
                    Text("Size: ${height.toInt()} dp")
                    Slider(value = height, onValueChange = { height = it; prefs.heightDp = it.toInt() },
                        onValueChangeFinished = ::refreshOverlay, valueRange = 72f..280f)
                    Text("Position along bottom")
                    Slider(value = position, onValueChange = { position = it; prefs.position = it },
                        onValueChangeFinished = ::refreshOverlay, valueRange = 0f..1f)
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = ::enableOverlay, enabled = artworkName != null, modifier = Modifier.fillMaxWidth()) {
                        Text(if (running) "Refresh floating pet" else "Show floating pet")
                    }
                    if (running) OutlinedButton(onClick = {
                        stopService(Intent(this@PetOverlayActivity, PetOverlayService::class.java))
                        running = false
                    }, modifier = Modifier.fillMaxWidth()) { Text("Turn off floating pet") }
                    OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        running = PetOverlayService.running
        artworkName = PetOverlayArtwork.read(this)?.name
    }

    private fun enableOverlay() {
        message = null
        if (Settings.canDrawOverlays(this)) startOverlay() else {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            runCatching { permission.launch(intent) }.onFailure { message = "Could not open Android's overlay permission settings." }
        }
    }

    private fun startOverlay() {
        if (PetOverlayArtwork.read(this) == null) {
            message = "Choose a pet in your connected profile first."
            return
        }
        runCatching { ContextCompat.startForegroundService(this, Intent(this, PetOverlayService::class.java)) }
            .onSuccess { running = true; message = null }
            .onFailure { message = "Could not start the floating pet: ${it.localizedMessage}" }
    }

    private fun refreshOverlay() {
        if (PetOverlayService.running) startService(Intent(this, PetOverlayService::class.java))
    }
}




