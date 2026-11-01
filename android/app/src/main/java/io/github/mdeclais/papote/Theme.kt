package io.github.mdeclais.papote

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/** Couleurs de l'icône Papote, déclinées en clair et en sombre (comme sur iPhone). */
object Palette {
    private val dark @Composable @ReadOnlyComposable get() = isSystemInDarkTheme()

    /** Fond de l'app : crème chaude / brun très foncé. */
    val background @Composable @ReadOnlyComposable get() = if (dark) Color(0xFF1F140D) else Color(0xFFFFF4DE)
    /** Panneaux : le jaune de l'icône / brun chaud. */
    val card @Composable @ReadOnlyComposable get() = if (dark) Color(0xFF3A2416) else Color(0xFFFAC775)
    /** Surfaces discrètes (pastilles, lignes de liste). */
    val surface @Composable @ReadOnlyComposable get() = if (dark) Color(0xFF2B1B12) else Color(0xFFFFFBF2)
    /** Le corail de la bulle. */
    val coral @Composable @ReadOnlyComposable get() = if (dark) Color(0xFFE8774F) else Color(0xFFD85A30)
    /** Le crème de l'onde, posé sur le corail. */
    val cream = Color(0xFFFAECE7)
    /** Le rouge du point REC. */
    val rec = Color(0xFFE24B4A)
    /** Texte posé sur le jaune ou le crème. */
    val ink @Composable @ReadOnlyComposable get() = if (dark) Color(0xFFFAECE7) else Color(0xFF4A1B0C)
    val inkSoft @Composable @ReadOnlyComposable get() = if (dark) Color(0xFFF0997B) else Color(0xFF993C1D)
    /** Bulle de l'intervieweur : jaune pâle. */
    val bubbleAlt @Composable @ReadOnlyComposable get() = if (dark) Color(0xFF4A3020) else Color(0xFFFDE3B0)
}

@Composable
fun PapoteTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFFE8774F), onPrimary = Color(0xFFFAECE7),
        background = Color(0xFF1F140D), surface = Color(0xFF2B1B12),
        onBackground = Color(0xFFFAECE7), onSurface = Color(0xFFFAECE7),
        surfaceContainer = Color(0xFF2B1B12), surfaceContainerHigh = Color(0xFF3A2416),
    ) else lightColorScheme(
        primary = Color(0xFFD85A30), onPrimary = Color(0xFFFAECE7),
        background = Color(0xFFFFF4DE), surface = Color(0xFFFFFBF2),
        onBackground = Color(0xFF4A1B0C), onSurface = Color(0xFF4A1B0C),
        surfaceContainer = Color(0xFFFFFBF2), surfaceContainerHigh = Color(0xFFFDE3B0),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
