package com.luke.tracks.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a music app asks for to read the phone's audio files: READ_MEDIA_AUDIO from Android 13. */
val musicPermission: String
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

fun Context.hasMusicPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, musicPermission) == PackageManager.PERMISSION_GRANTED

/**
 * The music files on the phone, from MediaStore: read off the main thread, and read again a
 * moment after anything changes (a download finishes, a card goes in). Ringtones and other
 * sounds are left out; see [DeviceAudio].
 */
class DeviceLibrary(private val context: Context, private val scope: CoroutineScope) {
    private val _tracks = MutableStateFlow<List<LibraryTrack>>(emptyList())
    private val _hasPermission = MutableStateFlow(context.hasMusicPermission())
    private val _loading = MutableStateFlow(true)
    private var observing = false
    private var pending: Job? = null

    val tracks: StateFlow<List<LibraryTrack>> = _tracks.asStateFlow()
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            pending?.cancel()
            pending = scope.launch {
                delay(1_000)
                refresh()
            }
        }
    }

    fun start() {
        scope.launch { refresh() }
    }

    suspend fun refresh() {
        val allowed = context.hasMusicPermission()
        _hasPermission.value = allowed
        if (!allowed) {
            _tracks.value = emptyList()
            _loading.value = false
            return
        }
        if (!observing) {
            observing = true
            runCatching {
                context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
            }
        }
        _tracks.value = withContext(Dispatchers.IO) { runCatching { query() }.onFailure { Log.w("Tracks", "MediaStore read failed", it) }.getOrDefault(emptyList()) }
        _loading.value = false
    }

    /**
     * Every external volume (the phone's storage and any SD card) one by one, as some phones
     * leave cards out of the combined "external" view; that view when none are listed.
     */
    private fun query(): List<LibraryTrack> {
        val volumes = runCatching { MediaStore.getExternalVolumeNames(context).toList() }.getOrDefault(emptyList())
            .ifEmpty { listOf(MediaStore.VOLUME_EXTERNAL) }
        return volumes.flatMap { volume ->
            val uri = MediaStore.Audio.Media.getContentUri(volume)
            runCatching { DeviceAudio.query { columns, selection -> context.contentResolver.query(uri, columns, selection, null, null) } }
                .onFailure { Log.w("Tracks", "Couldn't read the music on $volume", it) }
                .getOrDefault(emptyList())
        }.distinctBy { it.song.mediaId }
    }
}
