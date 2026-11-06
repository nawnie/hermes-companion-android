package dev.hermeskotlin.ui.components

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberLocalModelsLauncher(): () -> Unit {
    val context = LocalContext.current
    return { context.startActivity(Intent().setClassName(context, "dev.hermeskotlin.android.local.LocalModelsActivity")) }
}
