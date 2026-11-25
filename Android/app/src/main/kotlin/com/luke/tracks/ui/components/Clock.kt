package com.luke.tracks.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Now, for screens whose content follows the date or the hour (Play's mixes, its daily order
 * of songs waiting to be heard). Tests pin it, so what they see doesn't change with the day.
 */
val LocalClock = staticCompositionLocalOf<() -> Long> { System::currentTimeMillis }
