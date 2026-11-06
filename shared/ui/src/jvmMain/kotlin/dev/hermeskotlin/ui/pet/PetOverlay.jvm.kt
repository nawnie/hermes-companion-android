package dev.hermeskotlin.ui.pet

import androidx.compose.runtime.Composable
import dev.hermeskotlin.core.pet.PetSprite

@Composable
actual fun SyncPetOverlay(sprite: PetSprite?) = Unit

@Composable
actual fun rememberPetOverlayLauncher(): () -> Unit = {}
