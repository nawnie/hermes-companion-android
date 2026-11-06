package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.textMuted
import dev.hermeskotlin.designsystem.typography
import kotlin.math.roundToLong

/** Shows Hermes' rolling, gateway-measured generation speed when the gateway has timing samples. */
@Composable
internal fun GenerationSpeed(avgTps: Double?) {
    val rate = avgTps?.takeIf { it.isFinite() && it > 0.0 } ?: return
    val tenths = (rate * 10.0).roundToLong()
    val formatted = if (tenths % 10L == 0L) {
        (tenths / 10L).toString()
    } else {
        "${tenths / 10L}.${kotlin.math.abs(tenths % 10L)}"
    }

    Text(
        text = "$formatted tok/s · average",
        style = Theme[typography][caption],
        color = Theme[colors][textMuted],
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
