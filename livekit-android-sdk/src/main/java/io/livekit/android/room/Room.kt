/*
 * Copyright 2023-2024 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("unused")

package io.livekit.android.room

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.annotation.VisibleForTesting
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.ConnectOptions
import io.livekit.android.RoomOptions
import io.livekit.android.Version
import io.livekit.android.audio.AudioHandler
import io.livekit.android.audio.CommunicationWorkaround
import io.livekit.android.dagger.InjectionNames
import io.livekit.android.e2ee.E2EEManager
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.events.*
import io.livekit.android.memory.CloseableManager
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.participant.*
import io.livekit.android.room.track.*
import io.livekit.android.util.FlowObservable
import io.livekit.android.util.LKLog
import io.livekit.android.util.flow
import io.livekit.android.util.flowDelegate
import io.livekit.android.util.invoke
import io.livekit.android.webrtc.getFilteredStats
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import livekit.LivekitModels
import livekit.LivekitRtc
import livekit.org.webrtc.*
import javax.inject.Named

class Room
@AssistedInject
constructor(
    @Assisted private val context: Context,
    private val engine: RTCEngine,
    private val eglBase: EglBase,
    private val localParticipantFactory: LocalParticipant.Factory,
    private val defaultsManager: DefaultsManager,
    @Named(InjectionNames.DISPATCHER_DEFAULT)
    private val defaultDispatcher: CoroutineDispatcher,
    @Named(InjectionNames.DISPATCHER_IO)
    private val ioDispatcher: CoroutineDispatcher,
    val audioHandler: AudioHandler,
    private val closeableManager: CloseableManager,
    private val e2EEManagerFactory: E2EEManager.Factory,
    private val communicationWorkaround: CommunicationWorkaround,
) : RTCEngine.Listener, ParticipantListener {

    private lateinit var coroutineScope: CoroutineScope
    private val eventBus = BroadcastEventBus<RoomEvent>()
    val events = eventBus.readOnly()

    init {
        engine.listener = this
    }

    enum class State {
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        RECONNECTING,
    }

    /**
     * @suppress
     */
    enum class SimulateScenario {
        SPEAKER_UPDATE,
        NODE_FAILURE,
        MIGRATION,
        SERVER_LEAVE,
    }

    @Serializable
    @JvmInline
    value class Sid(val sid: String)

    /**
     * The session id of the room.
     *
     * Note: the sid may not be populated immediately upon [connect],
     * so using the suspend function [getSid] or listening to the flow
     * `room::sid.flow` is highly advised.
     */
    @FlowObservable
    @get:FlowObservable
    var sid: Sid? by flowDelegate(null)
        private set

    /**
     * Gets the sid of the room.
     *
     * If the sid is not yet available, will suspend until received.
     */
    suspend fun getSid(): Sid {
        return this@Room::sid.flow
            .filterNotNull()
            .first()
    }

    @FlowObservable
    @get:FlowObservable
    var name: String? by flowDelegate(null)
        private set

    @FlowObservable
    @get:FlowObservable
    var state: State by flowDelegate(State.DISCONNECTED) { new, old ->
        if (new != old) {
            when (new) {
                State.CONNECTING -> {
                    audioHandler.start()
                    communicationWorkaround.start()
                }

                State.DISCONNECTED -> {
                    audioHandler.stop()
                    communicationWorkaround.stop()
                }

                else -> {}
            }
        }
    }
        private set

    @FlowObservable
    @get:FlowObservable
    var metadata: String? by flowDelegate(null)
        private set

    @FlowObservable
    @get:FlowObservable
    var isRecording: Boolean by flowDelegate(false)
        private set

    /**
     *  end-to-end encryption manager
     */
    var e2eeManager: E2EEManager? = null

    /**
     * Automatically manage quality of subscribed video tracks, subscribe to the
     * an appropriate resolution based on the size of the video elements that tracks
     * are attached to.
     *
     * Also observes the visibility of attached tracks and pauses receiving data
     * if they are not visible.
     *
     * Defaults to false.
     */
    var adaptiveStream: Boolean = false

    /**
     * Dynamically pauses video layers that are not being consumed by any subscribers,
     * significantly reducing publishing CPU and bandwidth usage.
     *
     * Defaults to false.
     *
     * Will be enabled if SVC codecs (i.e. VP9/AV1) are used. Multi-codec simulcast
     * requires dynacast.
     */
    var dynacast: Boolean
        get() = localParticipant.dynacast
        set(value) {
            localParticipant.dynacast = value
        }

    /**
     * Options for end-to-end encryption. Must be setup prior to [connect].
     *
     * If null, e2ee will be disabled.
     */
    var e2eeOptions: E2EEOptions? = null

    /**
     * Default options to use when creating an audio track.
     */
    var audioTrackCaptureDefaults: LocalAudioTrackOptions by defaultsManager::audioTrackCaptureDefaults

    /**
     * Default options to use when publishing an audio track.
     */
    var audioTrackPublishDefaults: AudioTrackPublishDefaults by defaultsManager::audioTrackPublishDefaults

    /**
     * Default options to use when creating a video track.
     */
    var videoTrackCaptureDefaults: LocalVideoTrackOptions by defaultsManager::videoTrackCaptureDefaults

    /**
     * Default options to use when publishing a video track.
     */
    var videoTrackPublishDefaults: VideoTrackPublishDefaults by defaultsManager::videoTrackPublishDefaults

    val localParticipant: LocalParticipant = localParticipantFactory.create(dynacast = false).apply {
        internalListener = this@Room
    }

    private var mutableRemoteParticipants by flowDelegate(emptyMap<Participant.Identity, RemoteParticipant>())

    @FlowObservable
    @get:FlowObservable
    val remoteParticipants: Map<Participant.Identity, RemoteParticipant>
        get() = mutableRemoteParticipants

    private var sidToIdentity = mutableMapOf<Participant.Sid, Participant.Identity>()

    private var mutableActiveSpeakers by flowDelegate(emptyList<Participant>())

    @FlowObservable
    @get:FlowObservable
    val activeSpeakers: List<Participant>
        get() = mutableActiveSpeakers

    private var hasLostConnectivity: Boolean = false
    private var connectOptions: ConnectOptions = ConnectOptions()

    private fun getCurrentRoomOptions(): RoomOptions =
        RoomOptions(
            adaptiveStream = adaptiveStream,
            dynacast = dynacast,
            audioTrackCaptureDefaults = audioTrackCaptureDefaults,
            videoTrackCaptureDefaults = videoTrackCaptureDefaults,
            audioTrackPublishDefaults = audioTrackPublishDefaults,
            videoTrackPublishDefaults = videoTrackPublishDefaults,
            e2eeOptions = e2eeOptions,
        )

    /**
     * Connect to a LiveKit Room.
     *
     * @param url
     * @param token
     * @param options
     */
    @Throws(Exception::class)
    suspend fun connect(url: String, token: String, options: ConnectOptions = ConnectOptions()) {
        if (this::coroutineScope.isInitialized) {
            coroutineScope.cancel()
        }
        coroutineScope = CoroutineScope(defaultDispatcher + SupervisorJob())

        val roomOptions = getCurrentRoomOptions()

        // Setup local participant.
        localParticipant.reinitialize()
        coroutineScope.launch {
            localParticipant.events.collect {
                when (it) {
                    is ParticipantEvent.TrackPublished -> emitWhenConnected(
                        RoomEvent.TrackPublished(
                            room = this@Room,
                            publication = it.publication,
                            participant = it.participant,
                        ),
                    )

                    is ParticipantEvent.ParticipantPermissionsChanged -> emitWhenConnected(
                        RoomEvent.ParticipantPermissionsChanged(
                            room = this@Room,
                            participant = it.participant,
                            newPermissions = it.newPermissions,
                            oldPermissions = it.oldPermissions,
                        ),
                    )

                    is ParticipantEvent.MetadataChanged -> {
                        emitWhenConnected(
                            RoomEvent.ParticipantMetadataChanged(
                                this@Room,
                                it.participant,
                                it.prevMetadata,
                            ),
                        )
                    }

                    is ParticipantEvent.NameChanged -> {
                        emitWhenConnected(
                            RoomEvent.ParticipantNameChanged(
                                this@Room,
                                it.participant,
                                it.name,
                            ),
                        )
                    }

                    else -> {
                        // do nothing
                    }
                }
            }
        }

        state = State.CONNECTING
        connectOptions = options

        if (roomOptions.e2eeOptions != null) {
            e2eeManager = e2EEManagerFactory.create(roomOptions.e2eeOptions.keyProvider).apply {
                setup(this@Room) { event ->
                    coroutineScope.launch {
                        emitWhenConnected(event)
                    }
                }
            }
        }

        engine.join(url, token, options, roomOptions)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(networkRequest, networkCallback)

        if (options.audio) {
            val audioTrack = localParticipant.createAudioTrack()
            localParticipant.publishAudioTrack(audioTrack)
        }
        if (options.video) {
            val videoTrack = localParticipant.createVideoTrack()
            localParticipant.publishVideoTrack(videoTrack)
        }
    }

    /**
     * Disconnect from the room.
     */
    fun disconnect() {
        engine.client.sendLeave()
        handleDisconnect(DisconnectReason.CLIENT_INITIATED)
    }

    /**
     * Release all resources held by this object.
     *
     * Once called, this room object must not be used to connect to a server and a new one
     * must be created.
     */
    fun release() {
        closeableManager.close()
    }

    /**
     * @suppress
     */
    override fun onJoinResponse(response: LivekitRtc.JoinResponse) {
        LKLog.i { "Connected to server, server version: ${response.serverVersion}, client version: ${Version.CLIENT_VERSION}" }

        if (response.room.sid != null) {
            sid = Sid(response.room.sid)
        } else {
            sid = null
        }
        name = response.room.name
        metadata = response.room.metadata

        if (e2eeManager != null && !response.sifTrailer.isEmpty) {
            e2eeManager!!.keyProvider().setSifTrailer(response.sifTrailer.toByteArray())
        }

        if (response.room.activeRecording != isRecording) {
            isRecording = response.room.activeRecording
            eventBus.postEvent(RoomEvent.RecordingStatusChanged(this, isRecording), coroutineScope)
        }

        if (!response.hasParticipant()) {
            throw RoomException.ConnectException("server didn't return a local participant")
        }

        localParticipant.updateFromInfo(response.participant)

        if (response.otherParticipantsList.isNotEmpty()) {
            response.otherParticipantsList.forEach { info ->
                getOrCreateRemoteParticipant(Participant.Identity(info.identity), info)
            }
        }
    }

    private fun handleParticipantDisconnect(identity: Participant.Identity) {
        val newParticipants = mutableRemoteParticipants.toMutableMap()
        val removedParticipant = newParticipants.remove(identity) ?: return
        removedParticipant.trackPublications.values.toList().forEach { publication ->
            removedParticipant.unpublishTrack(publication.sid, true)
        }

        mutableRemoteParticipants = newParticipants
        eventBus.postEvent(RoomEvent.ParticipantDisconnected(this, removedParticipant), coroutineScope)
    }

    fun getParticipantBySid(sid: String): Participant? {
        return getParticipantBySid(Participant.Sid(sid))
    }

    fun getParticipantBySid(sid: Participant.Sid): Participant? {
        if (sid == localParticipant.sid) {
            return localParticipant
        } else {
            return remoteParticipants[sidToIdentity[sid]]
        }
    }

    fun getParticipantByIdentity(identity: String): Participant? {
        return getParticipantByIdentity(Participant.Identity(identity))
    }

    fun getParticipantByIdentity(identity: Participant.Identity): Participant? {
        if (identity == localParticipant.identity) {
            return localParticipant
        } else {
            return remoteParticipants[identity]
        }
    }

    @Synchronized
    private fun getOrCreateRemoteParticipant(
        identity: Participant.Identity,
        info: LivekitModels.ParticipantInfo,
    ): RemoteParticipant {
        var participant = remoteParticipants[identity]
        if (participant != null) {
            return participant
        }

        participant = RemoteParticipant(info, engine.client, ioDispatcher, defaultDispatcher)
        participant.internalListener = this

        coroutineScope.launch {
            participant.events.collect {
                when (it) {
                    is ParticipantEvent.TrackPublished -> {
                        if (state == State.CONNECTED) {
                            eventBus.postEvent(
                                RoomEvent.TrackPublished(
                                    room = this@Room,
                                    publication = it.publication,
                                    participant = it.participant,
                                ),
                            )
                        }
                    }

                    is ParticipantEvent.TrackStreamStateChanged -> eventBus.postEvent(
                        RoomEvent.TrackStreamStateChanged(
                            this@Room,
                            it.trackPublication,
                            it.streamState,
                        ),
                    )

                    is ParticipantEvent.TrackSubscriptionPermissionChanged -> eventBus.postEvent(
                        RoomEvent.TrackSubscriptionPermissionChanged(
                            this@Room,
                            it.participant,
                            it.trackPublication,
                            it.subscriptionAllowed,
                        ),
                    )

                    is ParticipantEvent.MetadataChanged -> {
                        emitWhenConnected(
                            RoomEvent.ParticipantMetadataChanged(
                                this@Room,
                                it.participant,
                                it.prevMetadata,
                            ),
                        )
                    }

                    is ParticipantEvent.NameChanged -> {
                        emitWhenConnected(
                            RoomEvent.ParticipantNameChanged(
                                this@Room,
                                it.participant,
                                it.name,
                            ),
                        )
                    }

                    is ParticipantEvent.ParticipantPermissionsChanged -> eventBus.postEvent(
                        RoomEvent.ParticipantPermissionsChanged(
                            room = this@Room,
                            participant = it.participant,
                            newPermissions = it.newPermissions,
                            oldPermissions = it.oldPermissions,
                        ),
                    )

                    else -> {
                        // do nothing
                    }
                }
            }
        }

        participant.updateFromInfo(info)

        val newRemoteParticipants = mutableRemoteParticipants.toMutableMap()
        newRemoteParticipants[identity] = participant
        mutableRemoteParticipants = newRemoteParticipants
        sidToIdentity[participant.sid] = identity

        return participant
    }

    private fun handleActiveSpeakersUpdate(speakerInfos: List<LivekitModels.SpeakerInfo>) {
        val speakers = mutableListOf<Participant>()
        val seenSids = mutableSetOf<Participant.Sid>()
        val localParticipant = localParticipant
        speakerInfos.forEach { speakerInfo ->
            val speakerSid = Participant.Sid(speakerInfo.sid)
            seenSids.add(speakerSid)

            val participant = getParticipantBySid(speakerSid) ?: return@forEach
            participant.audioLevel = speakerInfo.level
            participant.isSpeaking = true
            speakers.add(participant)
        }

        if (!seenSids.contains(localParticipant.sid)) {
            localParticipant.audioLevel = 0.0f
            localParticipant.isSpeaking = false
        }
        remoteParticipants.values
            .filterNot { seenSids.contains(it.sid) }
            .forEach {
                it.audioLevel = 0.0f
                it.isSpeaking = false
            }

        mutableActiveSpeakers = speakers.toList()
        eventBus.postEvent(RoomEvent.ActiveSpeakersChanged(this, mutableActiveSpeakers), coroutineScope)
    }

    private fun handleSpeakersChanged(speakerInfos: List<LivekitModels.SpeakerInfo>) {
        val updatedSpeakers = mutableMapOf<Participant.Sid, Participant>()
        activeSpeakers.forEach { participant ->
            updatedSpeakers[participant.sid] = participant
        }

        speakerInfos.forEach { speaker ->
            val speakerSid = Participant.Sid(speaker.sid)
            val participant = getParticipantBySid(speakerSid) ?: return@forEach

            participant.audioLevel = speaker.level
            participant.isSpeaking = speaker.active

            if (speaker.active) {
                updatedSpeakers[speakerSid] = participant
            } else {
                updatedSpeakers.remove(speakerSid)
            }
        }

        val updatedSpeakersList = updatedSpeakers.values.toList()
            .sortedBy { it.audioLevel }

        mutableActiveSpeakers = updatedSpeakersList.toList()
        eventBus.postEvent(RoomEvent.ActiveSpeakersChanged(this, mutableActiveSpeakers), coroutineScope)
    }

    private fun reconnect() {
        if (state == State.RECONNECTING) {
            return
        }
        engine.reconnect()
    }

    /**
     * Removes all participants and tracks from the room.
     */
    private fun cleanupRoom() {
        e2eeManager?.cleanUp()
        e2eeManager = null
        localParticipant.cleanup()
        remoteParticipants.keys.toMutableSet() // copy keys to avoid concurrent modifications.
            .forEach { sid -> handleParticipantDisconnect(sid) }

        sid = null
        metadata = null
        name = null
        isRecording = false
        sidToIdentity.clear()
    }

    private fun handleDisconnect(reason: DisconnectReason) {
        if (state == State.DISCONNECTED) {
            return
        }

        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(networkCallback)
        } catch (e: IllegalArgumentException) {
            // do nothing, may happen on older versions if attempting to unregister twice.
        }

        state = State.DISCONNECTED
        cleanupRoom()
        engine.close()

        localParticipant.dispose()

        // Ensure all observers see the disconnected before closing scope.
        runBlocking {
            eventBus.postEvent(RoomEvent.Disconnected(this@Room, null, reason), coroutineScope).join()
        }
        coroutineScope.cancel()
    }

    private fun sendSyncState() {
        // Whether we're sending subscribed tracks or tracks to unsubscribe.
        val sendUnsub = connectOptions.autoSubscribe
        val participantTracksList = mutableListOf<LivekitModels.ParticipantTracks>()
        for (participant in remoteParticipants.values) {
            val builder = LivekitModels.ParticipantTracks.newBuilder()
            builder.participantSid = participant.sid.value
            for (trackPub in participant.trackPublications.values) {
                val remoteTrackPub = (trackPub as? RemoteTrackPublication) ?: continue
                if (remoteTrackPub.subscribed != sendUnsub) {
                    builder.addTrackSids(remoteTrackPub.sid)
                }
            }

            if (builder.trackSidsCount > 0) {
                participantTracksList.add(builder.build())
            }
        }

        // backwards compatibility for protocol version < 6
        val trackSids = participantTracksList.map { it.trackSidsList }
            .flatten()

        val subscription = LivekitRtc.UpdateSubscription.newBuilder()
            .setSubscribe(!sendUnsub)
            .addAllParticipantTracks(participantTracksList)
            .addAllTrackSids(trackSids)
            .build()
        val publishedTracks = localParticipant.publishTracksInfo()
        engine.sendSyncState(subscription, publishedTracks)
    }

    /**
     * Sends a simulated scenario for the server to use.
     *
     * To be used for internal testing purposes only.
     * @suppress
     */
    fun sendSimulateScenario(scenario: LivekitRtc.SimulateScenario) {
        engine.client.sendSimulateScenario(scenario)
    }

    /**
     * Sends a simulated scenario for the server to use.
     *
     * To be used for internal testing purposes only.
     * @suppress
     */
    fun sendSimulateScenario(scenario: SimulateScenario) {
        val builder = LivekitRtc.SimulateScenario.newBuilder()
        when (scenario) {
            SimulateScenario.SPEAKER_UPDATE -> builder.speakerUpdate = 5
            SimulateScenario.NODE_FAILURE -> builder.nodeFailure = true
            SimulateScenario.MIGRATION -> builder.migration = true
            SimulateScenario.SERVER_LEAVE -> builder.serverLeave = true
        }
        sendSimulateScenario(builder.build())
    }

    /**
     * @suppress
     */
    @AssistedFactory
    interface Factory {
        fun create(context: Context): Room
    }

    // ------------------------------------- NetworkCallback -------------------------------------//
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        /**
         * @suppress
         */
        override fun onLost(network: Network) {
            // lost connection, flip to reconnecting
            hasLostConnectivity = true
        }

        /**
         * @suppress
         */
        override fun onAvailable(network: Network) {
            // only actually reconnect after connection is re-established
            if (!hasLostConnectivity) {
                return
            }
            LKLog.i { "network connection available, reconnecting" }
            reconnect()
            hasLostConnectivity = false
        }
    }

    // ----------------------------------- RTCEngine.Listener ------------------------------------//

    /**
     * @suppress
     */
    override fun onEngineConnected() {
        state = State.CONNECTED
        eventBus.postEvent(RoomEvent.Connected(this), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onEngineReconnected() {
        state = State.CONNECTED
        eventBus.postEvent(RoomEvent.Reconnected(this), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onEngineReconnecting() {
        state = State.RECONNECTING
        eventBus.postEvent(RoomEvent.Reconnecting(this), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onAddTrack(receiver: RtpReceiver, track: MediaStreamTrack, streams: Array<out MediaStream>) {
        if (streams.count() < 0) {
            LKLog.i { "add track with empty streams?" }
            return
        }

        var (participantSid, streamId) = unpackStreamId(streams.first().id)
        var trackSid = track.id()

        if (streamId != null && streamId.startsWith("TR")) {
            trackSid = streamId
        }
        val participant = getParticipantBySid(participantSid) as? RemoteParticipant

        if (participant == null) {
            LKLog.e { "Tried to add a track for a participant that is not present. sid: $participantSid" }
            return
        }

        val statsGetter = engine.createStatsGetter(receiver)
        participant.addSubscribedMediaTrack(
            track,
            trackSid!!,
            autoManageVideo = adaptiveStream,
            statsGetter = statsGetter,
            receiver = receiver,
        )
    }

    /**
     * @suppress
     */
    override fun onUpdateParticipants(updates: List<LivekitModels.ParticipantInfo>) {
        for (info in updates) {
            val participantSid = Participant.Sid(info.sid)
            // LiveKit server doesn't send identity info prior to version 1.5.2 in disconnect updates
            // so we try to map an empty identity to an already known sID manually

            @Suppress("NAME_SHADOWING") var info = info
            if (info.identity.isNullOrBlank()) {
                info = with(info.toBuilder()) {
                    identity = sidToIdentity[participantSid]?.value ?: ""
                    build()
                }
            }

            val participantIdentity = Participant.Identity(info.identity)

            if (localParticipant.identity == participantIdentity) {
                localParticipant.updateFromInfo(info)
                continue
            }

            val isNewParticipant = !remoteParticipants.contains(participantIdentity)

            if (info.state == LivekitModels.ParticipantInfo.State.DISCONNECTED) {
                handleParticipantDisconnect(participantIdentity)
            } else {
                val participant = getOrCreateRemoteParticipant(participantIdentity, info)
                if (isNewParticipant) {
                    eventBus.postEvent(RoomEvent.ParticipantConnected(this, participant), coroutineScope)
                } else {
                    participant.updateFromInfo(info)
                    sidToIdentity[participantSid] = participantIdentity
                }
            }
        }
    }

    /**
     * @suppress
     */
    override fun onActiveSpeakersUpdate(speakers: List<LivekitModels.SpeakerInfo>) {
        handleActiveSpeakersUpdate(speakers)
    }

    /**
     * @suppress
     */
    override fun onRemoteMuteChanged(trackSid: String, muted: Boolean) {
        localParticipant.onRemoteMuteChanged(trackSid, muted)
    }

    /**
     * @suppress
     */
    override fun onRoomUpdate(update: LivekitModels.Room) {
        if (update.sid != null) {
            sid = Sid(update.sid)
        }
        val oldMetadata = metadata
        metadata = update.metadata

        val oldIsRecording = isRecording
        isRecording = update.activeRecording

        if (oldMetadata != metadata) {
            eventBus.postEvent(RoomEvent.RoomMetadataChanged(this, metadata, oldMetadata), coroutineScope)
        }

        if (oldIsRecording != isRecording) {
            eventBus.postEvent(RoomEvent.RecordingStatusChanged(this, isRecording), coroutineScope)
        }
    }

    /**
     * @suppress
     */
    override fun onConnectionQuality(updates: List<LivekitRtc.ConnectionQualityInfo>) {
        updates.forEach { info ->
            val quality = ConnectionQuality.fromProto(info.quality)
            val participant = getParticipantBySid(info.participantSid) ?: return
            participant.connectionQuality = quality
            eventBus.postEvent(RoomEvent.ConnectionQualityChanged(this, participant, quality), coroutineScope)
        }
    }

    /**
     * @suppress
     */
    override fun onSpeakersChanged(speakers: List<LivekitModels.SpeakerInfo>) {
        handleSpeakersChanged(speakers)
    }

    /**
     * @suppress
     */
    override fun onUserPacket(packet: LivekitModels.UserPacket, kind: LivekitModels.DataPacket.Kind) {
        val participant = getParticipantBySid(packet.participantSid) as? RemoteParticipant
        val data = packet.payload.toByteArray()
        val topic = if (packet.hasTopic()) {
            packet.topic
        } else {
            null
        }

        eventBus.postEvent(RoomEvent.DataReceived(this, data, participant, topic), coroutineScope)
        participant?.onDataReceived(data, topic)
    }

    /**
     * @suppress
     */
    override fun onStreamStateUpdate(streamStates: List<LivekitRtc.StreamStateInfo>) {
        for (streamState in streamStates) {
            val participant = getParticipantBySid(streamState.participantSid) ?: continue
            val track = participant.trackPublications[streamState.trackSid] ?: continue

            track.track?.streamState = Track.StreamState.fromProto(streamState.state)
        }
    }

    /**
     * @suppress
     */
    override fun onSubscribedQualityUpdate(subscribedQualityUpdate: LivekitRtc.SubscribedQualityUpdate) {
        localParticipant.handleSubscribedQualityUpdate(subscribedQualityUpdate)
    }

    /**
     * @suppress
     */
    override fun onSubscriptionPermissionUpdate(subscriptionPermissionUpdate: LivekitRtc.SubscriptionPermissionUpdate) {
        val participant = getParticipantBySid(subscriptionPermissionUpdate.participantSid) as? RemoteParticipant ?: return
        participant.onSubscriptionPermissionUpdate(subscriptionPermissionUpdate)
    }

    /**
     * @suppress
     */
    override fun onEngineDisconnected(reason: DisconnectReason) {
        LKLog.v { "engine did disconnect: $reason" }
        handleDisconnect(reason)
    }

    /**
     * @suppress
     */
    override fun onFailToConnect(error: Throwable) {
        // scope will likely be closed already here, so force it out of scope.
        eventBus.tryPostEvent(RoomEvent.FailedToConnect(this, error))
    }

    /**
     * @suppress
     */
    override fun onSignalConnected(isResume: Boolean) {
        if (isResume) {
            // during resume reconnection, need to send sync state upon signal connection.
            sendSyncState()
        }
    }

    /**
     * @suppress
     */
    override fun onFullReconnecting() {
        localParticipant.prepareForFullReconnect()
        remoteParticipants.keys.toMutableSet() // copy keys to avoid concurrent modifications.
            .forEach { identity -> handleParticipantDisconnect(identity) }
    }

    /**
     * @suppress
     */
    override suspend fun onPostReconnect(isFullReconnect: Boolean) {
        if (isFullReconnect) {
            localParticipant.republishTracks()
        } else {
            val remoteParticipants = remoteParticipants.values.toList()
            for (participant in remoteParticipants) {
                val pubs = participant.trackPublications.values.toList()
                for (pub in pubs) {
                    val remotePub = pub as? RemoteTrackPublication ?: continue
                    if (remotePub.subscribed) {
                        remotePub.sendUpdateTrackSettings.invoke()
                    }
                }
            }
        }
    }

    /**
     * @suppress
     */
    override fun onLocalTrackUnpublished(trackUnpublished: LivekitRtc.TrackUnpublishedResponse) {
        localParticipant.handleLocalTrackUnpublished(trackUnpublished)
    }

    // ------------------------------- ParticipantListener --------------------------------//
    /**
     * This is called for both Local and Remote participants
     * @suppress
     */
    override fun onMetadataChanged(participant: Participant, prevMetadata: String?) {
    }

    /** @suppress */
    override fun onTrackMuted(publication: TrackPublication, participant: Participant) {
        eventBus.postEvent(RoomEvent.TrackMuted(this, publication, participant), coroutineScope)
    }

    /** @suppress */
    override fun onTrackUnmuted(publication: TrackPublication, participant: Participant) {
        eventBus.postEvent(RoomEvent.TrackUnmuted(this, publication, participant), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onTrackUnpublished(publication: RemoteTrackPublication, participant: RemoteParticipant) {
        eventBus.postEvent(RoomEvent.TrackUnpublished(this, publication, participant), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onTrackPublished(publication: LocalTrackPublication, participant: LocalParticipant) {
        if (e2eeManager != null) {
            e2eeManager!!.addPublishedTrack(publication.track!!, publication, participant, this)
        }
        eventBus.postEvent(RoomEvent.TrackPublished(this, publication, participant), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onTrackUnpublished(publication: LocalTrackPublication, participant: LocalParticipant) {
        e2eeManager?.let { e2eeManager ->
            e2eeManager!!.removePublishedTrack(publication.track!!, publication, participant, this)
        }
        eventBus.postEvent(RoomEvent.TrackUnpublished(this, publication, participant), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onTrackSubscribed(track: Track, publication: RemoteTrackPublication, participant: RemoteParticipant) {
        if (e2eeManager != null) {
            e2eeManager!!.addSubscribedTrack(track, publication, participant, this)
        }
        eventBus.postEvent(RoomEvent.TrackSubscribed(this, track, publication, participant), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onTrackSubscriptionFailed(
        sid: String,
        exception: Exception,
        participant: RemoteParticipant,
    ) {
        eventBus.postEvent(RoomEvent.TrackSubscriptionFailed(this, sid, exception, participant), coroutineScope)
    }

    /**
     * @suppress
     */
    override fun onTrackUnsubscribed(
        track: Track,
        publication: RemoteTrackPublication,
        participant: RemoteParticipant,
    ) {
        e2eeManager?.let { e2eeManager ->
            e2eeManager!!.removeSubscribedTrack(track, publication, participant, this)
        }
        eventBus.postEvent(RoomEvent.TrackUnsubscribed(this, track, publication, participant), coroutineScope)
    }

    /**
     * Initialize a [SurfaceViewRenderer] for rendering a video from this room.
     */
    // TODO(@dl): can this be moved out of Room/SDK?
    fun initVideoRenderer(viewRenderer: SurfaceViewRenderer) {
        viewRenderer.init(eglBase.eglBaseContext, null)
        viewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        viewRenderer.setEnableHardwareScaler(false)
    }

    /**
     * Initialize a [TextureViewRenderer] for rendering a video from this room.
     */
    // TODO(@dl): can this be moved out of Room/SDK?
    fun initVideoRenderer(viewRenderer: TextureViewRenderer) {
        viewRenderer.init(eglBase.eglBaseContext, null)
        viewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        viewRenderer.setEnableHardwareScaler(false)
    }

    private suspend fun emitWhenConnected(event: RoomEvent) {
        if (state == State.CONNECTED) {
            eventBus.postEvent(event)
        }
    }

    /**
     * Get stats for the publisher peer connection.
     *
     * @see getSubscriberRTCStats
     * @see getFilteredStats
     */
    fun getPublisherRTCStats(callback: RTCStatsCollectorCallback) = engine.getPublisherRTCStats(callback)

    /**
     * Get stats for the subscriber peer connection.
     *
     * @see getPublisherRTCStats
     * @see getFilteredStats
     */
    fun getSubscriberRTCStats(callback: RTCStatsCollectorCallback) = engine.getSubscriberRTCStats(callback)

    // Debug options

    /**
     * @suppress
     */
    @VisibleForTesting
    fun setReconnectionType(reconnectType: ReconnectType) {
        engine.reconnectType = reconnectType
    }
}

sealed class RoomException(message: String? = null, cause: Throwable? = null) :
    Exception(message, cause) {
    class ConnectException(message: String? = null, cause: Throwable? = null) :
        RoomException(message, cause)
}

internal fun unpackStreamId(packed: String): Pair<String, String?> {
    val parts = packed.split('|')
    if (parts.size != 2) {
        return Pair(packed, null)
    }
    return Pair(parts[0], parts[1])
}
