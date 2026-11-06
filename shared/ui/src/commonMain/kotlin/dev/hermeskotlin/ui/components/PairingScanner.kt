package dev.hermeskotlin.ui.components

import androidx.compose.runtime.Composable

@Composable
expect fun rememberPairingScanner(onResult: (String) -> Unit): () -> Unit
