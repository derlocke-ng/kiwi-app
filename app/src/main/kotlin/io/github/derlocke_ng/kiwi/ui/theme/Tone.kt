package io.github.derlocke_ng.kiwi.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.derlocke_ng.kiwi.core.State

/** The colors of a state: [color] for a dot or a word, [container] behind [onContainer] for a card. */
data class Tone(val color: Color, val container: Color, val onContainer: Color)

/**
 * Green, amber and red mean the same on every phone: they do not follow the wallpaper as the
 * rest of the colors does.
 */
@Composable
fun State.tone(): Tone {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        State.Ok -> if (dark) Tone(Color(0xFF8FD694), Color(0xFF1F4A25), Color(0xFFC9F2CC)) else Tone(Color(0xFF2E7D32), Color(0xFFD9F2DA), Color(0xFF0E3A12))
        State.Warn -> if (dark) Tone(Color(0xFFFFBE5C), Color(0xFF5C3B00), Color(0xFFFFE2B8)) else Tone(Color(0xFFA85F00), Color(0xFFFFE7C4), Color(0xFF3D2500))
        State.Bad, State.Down -> Tone(scheme.error, scheme.errorContainer, scheme.onErrorContainer)
        State.Unknown -> Tone(scheme.outline, scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
    }
}
