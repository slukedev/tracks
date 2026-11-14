package com.luke.tracks.system

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.luke.tracks.R
import com.luke.tracks.graph
import com.luke.tracks.playback.PlayerState
import com.luke.tracks.playback.QueueOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Tracks Radio in Quick Settings: tap to start it, tap again to pause, and the tile says
 * what's playing. Press and hold opens Tracks (MainActivity takes QS_TILE_PREFERENCES).
 */
class RadioTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        watching?.cancel()
        watching = scope.launch { graph.player.state.collect(::show) }
    }

    override fun onStopListening() {
        watching?.cancel()
        watching = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val toggle = {
            val state = graph.player.state.value
            if (state.isRadio) graph.player.playPause() else graph.player.startRadio()
        }
        if (isLocked) unlockAndRun(toggle) else toggle()
    }

    private fun show(state: PlayerState) {
        val tile = qsTile ?: return
        val on = state.isRadio && (state.isPlaying || state.isBuffering)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_radio)
        tile.subtitle = when {
            state.isRadio && state.current != null && on -> state.current.title
            state.isRadio && state.current != null -> "Paused"
            else -> "Off"
        }
        tile.contentDescription = listOfNotNull(tile.label, tile.subtitle).joinToString(", ")
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_radio)
        tile.updateTile()
    }
}

private val PlayerState.isRadio: Boolean get() = origin?.kind == QueueOrigin.Kind.Radio
