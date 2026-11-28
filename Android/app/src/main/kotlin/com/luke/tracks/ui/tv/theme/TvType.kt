package com.luke.tracks.ui.tv.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.luke.tracks.ui.theme.TracksTypography

/**
 * The type scale read from across a room, in the app's Google Sans Flex: display 57/45/36,
 * headline 32/28/24, title 22/18/16, body 16/14/12, label 14/12/11.
 */
private val phone = TracksTypography

private fun TextStyle.sized(size: TextUnit, line: TextUnit) = copy(fontSize = size, lineHeight = line)

private val displayLarge = phone.displayLarge.sized(57.sp, 64.sp)
private val displayMedium = phone.displayMedium.sized(45.sp, 52.sp)
private val displaySmall = phone.displaySmall.sized(36.sp, 44.sp)
private val headlineLarge = phone.headlineLarge.sized(32.sp, 40.sp)
private val headlineMedium = phone.headlineMedium.sized(28.sp, 36.sp)
private val headlineSmall = phone.headlineSmall.sized(24.sp, 32.sp)
private val titleLarge = phone.titleLarge.sized(22.sp, 28.sp)
private val titleMedium = phone.titleMedium.sized(18.sp, 24.sp)
private val titleSmall = phone.titleSmall.sized(16.sp, 22.sp)
private val bodyLarge = phone.bodyLarge.sized(16.sp, 22.sp)
private val bodyMedium = phone.bodyMedium.sized(14.sp, 20.sp)
private val bodySmall = phone.bodySmall.sized(12.sp, 16.sp)
private val labelLarge = phone.labelLarge.sized(14.sp, 20.sp)
private val labelMedium = phone.labelMedium.sized(12.sp, 16.sp)
private val labelSmall = phone.labelSmall.sized(11.sp, 16.sp)

/** For tv-material components and TV screens' text. */
val TvTypography = androidx.tv.material3.Typography(
    displayLarge = displayLarge,
    displayMedium = displayMedium,
    displaySmall = displaySmall,
    headlineLarge = headlineLarge,
    headlineMedium = headlineMedium,
    headlineSmall = headlineSmall,
    titleLarge = titleLarge,
    titleMedium = titleMedium,
    titleSmall = titleSmall,
    bodyLarge = bodyLarge,
    bodyMedium = bodyMedium,
    bodySmall = bodySmall,
    labelLarge = labelLarge,
    labelMedium = labelMedium,
    labelSmall = labelSmall,
)

private fun TextStyle.bold() = copy(fontWeight = FontWeight.Bold)
private fun TextStyle.medium() = copy(fontWeight = FontWeight.Medium)

/** The same scale for the shared Material 3 drawing (charts, art) that TV screens reuse. */
val TvM3Typography = phone.copy(
    displayLarge = displayLarge,
    displayMedium = displayMedium,
    displaySmall = displaySmall,
    headlineLarge = headlineLarge,
    headlineMedium = headlineMedium,
    headlineSmall = headlineSmall,
    titleLarge = titleLarge,
    titleMedium = titleMedium,
    titleSmall = titleSmall,
    bodyLarge = bodyLarge,
    bodyMedium = bodyMedium,
    bodySmall = bodySmall,
    labelLarge = labelLarge,
    labelMedium = labelMedium,
    labelSmall = labelSmall,
    displayLargeEmphasized = displayLarge.bold(),
    displayMediumEmphasized = displayMedium.bold(),
    displaySmallEmphasized = displaySmall.bold(),
    headlineLargeEmphasized = headlineLarge.bold(),
    headlineMediumEmphasized = headlineMedium.bold(),
    headlineSmallEmphasized = headlineSmall.bold(),
    titleLargeEmphasized = titleLarge.bold(),
    titleMediumEmphasized = titleMedium.bold(),
    titleSmallEmphasized = titleSmall.bold(),
    bodyLargeEmphasized = bodyLarge.medium(),
    bodyMediumEmphasized = bodyMedium.medium(),
    bodySmallEmphasized = bodySmall.medium(),
    labelLargeEmphasized = labelLarge.bold(),
    labelMediumEmphasized = labelMedium.bold(),
    labelSmallEmphasized = labelSmall.bold(),
)
