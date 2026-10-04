package com.xmcam.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Temas disponibles en la app.
 *
 * @property label       nombre mostrado en Ajustes > Temas.
 * @property description frase corta que explica el aspecto del tema.
 * @property dark        true si es un tema oscuro (se usa para decidir el tema según el sistema).
 */
enum class AppThemeId(val label: String, val description: String, val dark: Boolean) {
    NVR_DARK("NVR oscuro", "Negro azulado con acento azul. Aspecto de grabador profesional.", true),
    MATERIAL_LIGHT("Material You claro", "Fondo claro, tarjetas redondeadas y acento violeta.", false),
    AMOLED("AMOLED negro", "Negro puro: ahorra batería en pantallas OLED.", true),
    CYBER("Cyber verde", "Fondo casi negro con acento verde neón, estilo seguridad.", true),
    MINIMAL("Minimal blanco", "Blanco y gris con mucho aire. Sobrio y limpio.", false)
}

/**
 * Devuelve el [ColorScheme] de Material 3 que corresponde a este tema.
 * Todas las pantallas leen estos colores a través de MaterialTheme, así que cambiar de tema
 * no requiere tocar ninguna pantalla.
 */
fun AppThemeId.colorScheme(): ColorScheme = when (this) {
    AppThemeId.NVR_DARK -> darkColorScheme(
        primary = Color(0xFF3B82F6), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF1D3A6B), onPrimaryContainer = Color(0xFFD6E4FF),
        secondaryContainer = Color(0xFF1E2A3F), onSecondaryContainer = Color(0xFFBFD4FF),
        background = Color(0xFF0E1116), onBackground = Color(0xFFE6E9EF),
        surface = Color(0xFF0E1116), onSurface = Color(0xFFE6E9EF),
        surfaceVariant = Color(0xFF171B22), onSurfaceVariant = Color(0xFF8B93A1),
        outline = Color(0xFF2A3140), error = Color(0xFFEF4444)
    )
    AppThemeId.MATERIAL_LIGHT -> lightColorScheme(
        primary = Color(0xFF6750A4), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFEADDFF), onPrimaryContainer = Color(0xFF21005D),
        secondaryContainer = Color(0xFFE8DEF8), onSecondaryContainer = Color(0xFF1D192B),
        background = Color(0xFFFBF8FF), onBackground = Color(0xFF1D1B20),
        surface = Color(0xFFFBF8FF), onSurface = Color(0xFF1D1B20),
        surfaceVariant = Color(0xFFF3EDF7), onSurfaceVariant = Color(0xFF49454F),
        outline = Color(0xFF79747E), error = Color(0xFFB3261E)
    )
    AppThemeId.AMOLED -> darkColorScheme(
        primary = Color(0xFF8AB4F8), onPrimary = Color(0xFF00172E),
        primaryContainer = Color(0xFF1A2A44), onPrimaryContainer = Color(0xFFD3E3FD),
        secondaryContainer = Color(0xFF181818), onSecondaryContainer = Color(0xFFE3E3E3),
        background = Color(0xFF000000), onBackground = Color(0xFFEDEDED),
        surface = Color(0xFF000000), onSurface = Color(0xFFEDEDED),
        surfaceVariant = Color(0xFF121212), onSurfaceVariant = Color(0xFF9A9A9A),
        outline = Color(0xFF2B2B2B), error = Color(0xFFFF6B6B)
    )
    AppThemeId.CYBER -> darkColorScheme(
        primary = Color(0xFF00FF9C), onPrimary = Color(0xFF00110A),
        primaryContainer = Color(0xFF003D26), onPrimaryContainer = Color(0xFF7CFFC8),
        secondaryContainer = Color(0xFF0F2A21), onSecondaryContainer = Color(0xFF9CFFD6),
        background = Color(0xFF05080A), onBackground = Color(0xFFD5FFF0),
        surface = Color(0xFF05080A), onSurface = Color(0xFFD5FFF0),
        surfaceVariant = Color(0xFF0B1A15), onSurfaceVariant = Color(0xFF6FA891),
        outline = Color(0xFF1E4D3A), error = Color(0xFFFF5370)
    )
    AppThemeId.MINIMAL -> lightColorScheme(
        primary = Color(0xFF111111), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE4E4E7), onPrimaryContainer = Color(0xFF111111),
        secondaryContainer = Color(0xFFF0F0F2), onSecondaryContainer = Color(0xFF27272A),
        background = Color(0xFFFFFFFF), onBackground = Color(0xFF111111),
        surface = Color(0xFFFFFFFF), onSurface = Color(0xFF111111),
        surfaceVariant = Color(0xFFF4F4F5), onSurfaceVariant = Color(0xFF71717A),
        outline = Color(0xFFD4D4D8), error = Color(0xFFDC2626)
    )
}

/**
 * Decide qué tema aplicar realmente.
 *
 * - Si [followSystem] es false, se usa [selected] tal cual.
 * - Si es true, se respeta [selected] cuando coincide con el modo del sistema (claro/oscuro);
 *   si no coincide, se usa el tema por defecto de ese modo (NVR oscuro o Material You claro).
 *
 * @param selected     tema elegido por el usuario.
 * @param followSystem true si debe seguir el modo claro/oscuro del sistema.
 * @param systemDark   true si el sistema está en modo oscuro.
 */
fun resolveTheme(selected: AppThemeId, followSystem: Boolean, systemDark: Boolean): AppThemeId = when {
    !followSystem -> selected
    selected.dark == systemDark -> selected
    systemDark -> AppThemeId.NVR_DARK
    else -> AppThemeId.MATERIAL_LIGHT
}
