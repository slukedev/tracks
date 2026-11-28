package com.luke.tracks.speakers

import com.luke.tracks.library.trackside.TracksideClient
import com.luke.tracks.library.trackside.TracksideSpeaker
import com.luke.tracks.library.trackside.TracksideSpeakerCommand
import com.luke.tracks.library.trackside.TracksideSpeakerList
import com.luke.tracks.library.trackside.TracksideSpeakerPatch
import com.luke.tracks.library.trackside.TracksideSpeakerPlayRequest
import com.luke.tracks.library.trackside.TracksideSpeakerPlayback
import com.luke.tracks.library.trackside.TracksideSpeakerQueue
import com.luke.tracks.library.trackside.TracksideSpeakerTransfer

/** What the speakers need of one server: its Trackside client in the app, a fake in tests. */
interface SpeakerClient {
    suspend fun access(): TracksideClient.SpeakerAccess

    /** Null when nothing changed since [version]. */
    suspend fun speakers(version: String?): Pair<TracksideSpeakerList, String?>?
    suspend fun speaker(id: String): TracksideSpeaker
    suspend fun queue(id: String, limit: Int): TracksideSpeakerQueue
    suspend fun playback(id: String, command: TracksideSpeakerPlayback): TracksideSpeakerCommand
    suspend fun patch(id: String, patch: TracksideSpeakerPatch): TracksideSpeakerCommand
    suspend fun members(id: String, memberIds: List<String>): TracksideSpeakerCommand
    suspend fun leaveGroup(id: String): TracksideSpeakerCommand
    suspend fun play(id: String, request: TracksideSpeakerPlayRequest): TracksideSpeakerCommand
    suspend fun transfer(id: String, transfer: TracksideSpeakerTransfer): TracksideSpeakerCommand

    /** How far the server's clock runs ahead of this phone's. */
    val clockSkewMs: Long
}

class TracksideSpeakerClient(private val client: TracksideClient) : SpeakerClient {
    override suspend fun access() = client.speakerAccess()
    override suspend fun speakers(version: String?) = client.speakers(version)
    override suspend fun speaker(id: String) = client.speaker(id)
    override suspend fun queue(id: String, limit: Int) = client.speakerQueue(id, limit = limit)
    override suspend fun playback(id: String, command: TracksideSpeakerPlayback) = client.speakerPlayback(id, command)
    override suspend fun patch(id: String, patch: TracksideSpeakerPatch) = client.patchSpeaker(id, patch)
    override suspend fun members(id: String, memberIds: List<String>) = client.setSpeakerMembers(id, memberIds)
    override suspend fun leaveGroup(id: String) = client.leaveSpeakerGroup(id)
    override suspend fun play(id: String, request: TracksideSpeakerPlayRequest) = client.playOnSpeaker(id, request)
    override suspend fun transfer(id: String, transfer: TracksideSpeakerTransfer) = client.transferSpeaker(id, transfer)
    override val clockSkewMs: Long get() = client.clockSkewMs
}
