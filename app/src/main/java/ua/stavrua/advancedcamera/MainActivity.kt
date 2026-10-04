package ua.stavrua.advancedcamera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF80CBC4),
                    onPrimary = Color(0xFF06201D),
                    secondary = Color(0xFFFFB74D),
                    background = Color(0xFF101418),
                    surface = Color(0xFF1A2228),
                    onSurface = Color(0xFFE7EEF2),
                ),
            ) {
                CameraScreen()
            }
        }
    }
}
