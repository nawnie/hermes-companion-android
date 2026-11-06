package dev.hermeskotlin.ui.resources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.resources.ComputerResources
import dev.hermeskotlin.core.resources.ComputerResourcesApi
import dev.hermeskotlin.core.resources.asGiB
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.typography
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.round

/** A point-in-time view of the host running Hermes. Missing endpoint data is shown as unknown, never estimated. */
@Composable
fun ResourceStatusDialog(onDismissRequest: () -> Unit) {
    val api = koinInject<ComputerResourcesApi>()
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<ComputerResources?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun refresh() {
        if (loading) return
        scope.launch {
            loading = true
            try {
                snapshot = api.read()
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(api) {
        loading = true
        try {
            snapshot = api.read()
        } finally {
            loading = false
        }
    }

    Dialog(
        visible = true,
        onDismissRequest = onDismissRequest,
        title = "Computer resources",
        message = "Live readings from the computer running Hermes.",
        actions = {
            Button("Close", onClick = onDismissRequest, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button(if (loading) "Checking…" else "Refresh", onClick = ::refresh, enabled = !loading, size = ButtonSize.Small)
        },
        content = {
            val current = snapshot
            if (current == null) {
                ResourceValueRow("Status", if (loading) "Checking this computer…" else "No reading yet")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ResourceSection("PROCESSOR")
                    ResourceValueRow(
                        "CPU use",
                        current.cpuPercent?.let { "${it.oneDecimal()}%" } ?: current.systemError?.let { "Unavailable: $it" } ?: "Not reported",
                    )
                    ResourceValueRow("Logical processors", current.logicalCpuCount?.toString() ?: current.systemError?.let { "Unavailable: $it" } ?: "Not reported")

                    ResourceSection("MEMORY")
                    ResourceValueRow(
                        "RAM in use",
                        current.memoryUsedBytes?.let(::bytes) ?: current.systemError?.let { "Unavailable: $it" } ?: "Not reported",
                    )
                    ResourceValueRow("RAM available", current.memoryAvailableBytes?.let(::bytes) ?: current.systemError?.let { "Unavailable: $it" } ?: current.hardwareError?.let { "Unavailable: $it" } ?: "Not reported")
                    ResourceValueRow("RAM total", current.memoryTotalBytes?.let(::bytes) ?: current.systemError?.let { "Unavailable: $it" } ?: current.hardwareError?.let { "Unavailable: $it" } ?: "Not reported")

                    ResourceSection("GRAPHICS")
                    ResourceValueRow("GPU", current.gpuName ?: current.hardwareError?.let { "Unavailable: $it" } ?: "Not reported")
                    ResourceValueRow("GPU use", current.gpuUtilPercent?.let { "${it.oneDecimal()}%" } ?: current.hardwareError?.let { "Unavailable: $it" } ?: "Not reported")
                    val gpuMemory = if (current.gpuMemoryIsShared == true) "Shared GPU memory" else "VRAM"
                    ResourceValueRow("$gpuMemory in use", current.vramUsedBytes?.let(::bytes) ?: current.hardwareError?.let { "Unavailable: $it" } ?: "Not reported")
                    ResourceValueRow("$gpuMemory total", current.vramTotalBytes?.let(::bytes) ?: current.hardwareError?.let { "Unavailable: $it" } ?: "Not reported")
                }
            }
        },
    )
}

@Composable
private fun ResourceSection(title: String) {
    Text(
        title,
        modifier = Modifier.padding(top = 4.dp),
        style = Theme[typography][label],
        color = Theme[colors][textSecondary],
    )
}

@Composable
private fun ResourceValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp), style = Theme[typography][body], color = Theme[colors][textSecondary])
        Text(value, modifier = Modifier.weight(1f), textAlign = TextAlign.End, style = Theme[typography][body], color = Theme[colors][text])
    }
}

private fun Double.oneDecimal(): String = (round(this * 10.0) / 10.0).toString()

private fun bytes(value: Long): String = "${value.asGiB().oneDecimal()} GiB"
