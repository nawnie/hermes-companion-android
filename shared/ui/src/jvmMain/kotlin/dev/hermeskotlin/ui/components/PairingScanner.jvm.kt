package dev.hermeskotlin.ui.components

import androidx.compose.runtime.Composable

@Composable
actual fun rememberPairingScanner(onResult: (String) -> Unit): () -> Unit = {}
