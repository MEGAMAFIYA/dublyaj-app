package uz.dublyaj.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7C8CFF),
    secondary = Color(0xFF9BA4FF),
    background = Color(0xFF12142B),
    surface = Color(0xFF1B1F3B)
)

@Composable
fun DublyajTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
