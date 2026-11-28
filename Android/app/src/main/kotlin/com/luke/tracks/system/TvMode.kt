package com.luke.tracks.system

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which interface Settings asked for. [Automatic] follows the hardware. */
enum class TvModeChoice { Automatic, On, Off }

/** Whether the TV layout shows: as chosen, else on TV hardware. */
fun tvLayout(hardwareTv: Boolean, choice: TvModeChoice): Boolean = when (choice) {
    TvModeChoice.Automatic -> hardwareTv
    TvModeChoice.On -> true
    TvModeChoice.Off -> false
}

/** What turning TV Mode [on] or off stores: Automatic when that's what the hardware would show anyway. */
fun tvModeChoice(hardwareTv: Boolean, on: Boolean): TvModeChoice = when {
    on == hardwareTv -> TvModeChoice.Automatic
    on -> TvModeChoice.On
    else -> TvModeChoice.Off
}

/**
 * The TV Mode setting, separate from [FormFactor]: the hardware decides what can work (sources,
 * browsers, the share sheet), this decides which interface shows. Kept with the other values read
 * at launch, so the first frame is the right shell, and out of backups: it belongs to this device.
 */
class TvMode(private val prefs: SharedPreferences, val hardwareTv: Boolean) {
    private val stored = MutableStateFlow(read())
    private val effective = MutableStateFlow(tvLayout(hardwareTv, stored.value))

    val choice: StateFlow<TvModeChoice> = stored.asStateFlow()

    /** Whether the TV layout shows now. */
    val layout: StateFlow<Boolean> = effective.asStateFlow()

    fun set(choice: TvModeChoice) {
        prefs.edit().apply { if (choice == TvModeChoice.Automatic) remove(KEY) else putString(KEY, choice.name) }.apply()
        stored.value = choice
        effective.value = tvLayout(hardwareTv, choice)
    }

    /** The TV Mode switch. */
    fun set(on: Boolean) = set(tvModeChoice(hardwareTv, on))

    private fun read(): TvModeChoice =
        prefs.getString(KEY, null)?.let { stored -> TvModeChoice.entries.firstOrNull { it.name == stored } } ?: TvModeChoice.Automatic

    companion object {
        private const val KEY = "tvMode"

        /** The same small file the app reads sample data from at launch. */
        fun of(context: Context, hardwareTv: Boolean) =
            TvMode(context.getSharedPreferences("launch", Context.MODE_PRIVATE), hardwareTv)
    }
}
