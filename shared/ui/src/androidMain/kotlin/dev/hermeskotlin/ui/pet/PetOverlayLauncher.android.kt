package dev.hermeskotlin.ui.pet

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberPetOverlayLauncher(): () -> Unit {
    val context = LocalContext.current
    return { context.startActivity(Intent().setClassName(context, "dev.hermeskotlin.android.overlay.PetOverlayActivity")) }
}
