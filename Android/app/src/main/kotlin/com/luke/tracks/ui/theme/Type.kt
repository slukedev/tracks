package com.luke.tracks.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.luke.tracks.R

/**
 * Google Sans Flex, bundled (SIL OFL 1.1). Variable: weight, width, optical size and
 * roundness, which the expressive display styles lean on.
 */
private fun flex(weight: Int, round: Float = 0f, width: Float = 100f) = Font(
    R.font.google_sans_flex,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.width(width),
        FontVariation.Setting("ROND", round),
    ),
)

/** The everyday family: plain, as Google's apps set body text. */
val GoogleSansFlex = FontFamily(
    flex(300), flex(400), flex(500), flex(600), flex(700), flex(800),
)

/** Fully rounded, for the big numbers and headlines that carry the expressive feel. */
val GoogleSansFlexRounded = FontFamily(
    flex(400, round = 100f), flex(500, round = 100f), flex(600, round = 100f),
    flex(700, round = 100f), flex(800, round = 100f), flex(900, round = 100f),
)

/** Condensed and heavy, for labels that sit on artwork. */
val GoogleSansFlexCondensed = FontFamily(
    flex(600, round = 60f, width = 80f), flex(800, round = 60f, width = 80f),
)

private val base = Typography()

private fun TextStyle.flex() = copy(fontFamily = GoogleSansFlex)
private fun TextStyle.rounded() = copy(fontFamily = GoogleSansFlexRounded)

val TracksTypography = Typography(
    displayLarge = base.displayLarge.rounded(),
    displayMedium = base.displayMedium.rounded(),
    displaySmall = base.displaySmall.rounded(),
    headlineLarge = base.headlineLarge.rounded(),
    headlineMedium = base.headlineMedium.rounded(),
    headlineSmall = base.headlineSmall.rounded(),
    titleLarge = base.titleLarge.rounded(),
    titleMedium = base.titleMedium.flex().copy(fontWeight = FontWeight.Medium),
    titleSmall = base.titleSmall.flex().copy(fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.flex(),
    bodyMedium = base.bodyMedium.flex(),
    bodySmall = base.bodySmall.flex(),
    labelLarge = base.labelLarge.flex().copy(fontWeight = FontWeight.Medium),
    labelMedium = base.labelMedium.flex().copy(fontWeight = FontWeight.Medium),
    labelSmall = base.labelSmall.flex().copy(fontWeight = FontWeight.Medium),
    displayLargeEmphasized = base.displayLarge.rounded().copy(fontWeight = FontWeight.Bold),
    displayMediumEmphasized = base.displayMedium.rounded().copy(fontWeight = FontWeight.Bold),
    displaySmallEmphasized = base.displaySmall.rounded().copy(fontWeight = FontWeight.Bold),
    headlineLargeEmphasized = base.headlineLarge.rounded().copy(fontWeight = FontWeight.Bold),
    headlineMediumEmphasized = base.headlineMedium.rounded().copy(fontWeight = FontWeight.Bold),
    headlineSmallEmphasized = base.headlineSmall.rounded().copy(fontWeight = FontWeight.Bold),
    titleLargeEmphasized = base.titleLarge.rounded().copy(fontWeight = FontWeight.Bold),
    titleMediumEmphasized = base.titleMedium.flex().copy(fontWeight = FontWeight.Bold),
    titleSmallEmphasized = base.titleSmall.flex().copy(fontWeight = FontWeight.Bold),
    bodyLargeEmphasized = base.bodyLarge.flex().copy(fontWeight = FontWeight.Medium),
    bodyMediumEmphasized = base.bodyMedium.flex().copy(fontWeight = FontWeight.Medium),
    bodySmallEmphasized = base.bodySmall.flex().copy(fontWeight = FontWeight.Medium),
    labelLargeEmphasized = base.labelLarge.flex().copy(fontWeight = FontWeight.Bold),
    labelMediumEmphasized = base.labelMedium.flex().copy(fontWeight = FontWeight.Bold),
    labelSmallEmphasized = base.labelSmall.flex().copy(fontWeight = FontWeight.Bold),
)
