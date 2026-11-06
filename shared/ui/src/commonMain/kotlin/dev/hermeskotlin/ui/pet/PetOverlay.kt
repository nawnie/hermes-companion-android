package dev.hermeskotlin.ui.pet

import androidx.compose.runtime.Composable
import dev.hermeskotlin.core.pet.PetSprite

/** Retains the adopted pet for the phone's independently enabled floating companion. */
@Composable
expect fun SyncPetOverlay(sprite: PetSprite?)

@Composable
expect fun rememberPetOverlayLauncher(): () -> Unit
