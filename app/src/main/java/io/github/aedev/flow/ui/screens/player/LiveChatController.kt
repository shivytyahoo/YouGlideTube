package io.github.aedev.flow.ui.screens.player

import io.github.aedev.flow.data.model.LiveChatMessage
import io.github.aedev.flow.data.repository.LiveChatRepository
import io.github.aedev.flow.data.repository.LiveChatSendStatus
import io.github.aedev.flow.utils.ShivLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The live chat transcript and the loop that drips it in.
 *
 * Availability is probed as soon as a live video starts, because the whole chat affordance is
 * hidden until it resolves. The polling loop behind it is gated on [setPanelVisible]: a chat
 * nobody is looking at is a network round trip and a state write every few hundred milliseconds
 * for the length of a stream, and the player keeps its surfaces composed while they are hidden.
 * Leaving the panel keeps the transcript, so reopening it is instant.
 */
internal class LiveChatController(
    private val repository: LiveChatRepository,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private val _messages = MutableStateFlow<List<LiveChatMessage>>(emptyList())
    val messages: StateFlow<List<LiveChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private var videoId: String? = null
    private var channelId: String? = null
    private var ownHandle: String? = null
    private var ownName: String? = null
    private var enabled = true
    private var panelVisible = false
    private var probeJob: Job? = null
    private var dripJob: Job? = null
    private var pendingContinuation: String? = null
    private val seen = LinkedHashSet<String>()

    fun start(
        videoId: String,
        channelId: String? = null,
        ownHandle: String? = null,
        ownName: String? = null,
    ) {
        if (this.videoId == videoId) {
            // Same stream, fresher identity (e.g. the account finished loading
            // after the first start): refresh the own-message gate and re-mark
            // the rows already on screen instead of keeping a stale null.
            this.ownHandle = ownHandle
            this.ownName = ownName
            _messages.update { current -> current.map { it.markMine() } }
            ShivLog.i("liveChatOwn handle=$ownHandle name=$ownName refreshed=true")
            return
        }
        stop()
        this.videoId = videoId
        this.channelId = channelId
        this.ownHandle = ownHandle
        this.ownName = ownName
        ShivLog.i("liveChatOwn handle=$ownHandle name=$ownName")
        seen.clear()
        _messages.value = emptyList()
        _isAvailable.value = false
        _isLoading.value = enabled
        if (enabled) probe(videoId)
    }

    /** The Live chat setting: off, nothing is requested at all, not even whether a stream has chat. */
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        val current = videoId ?: return
        if (value) {
            _isLoading.value = true
            probe(current)
        } else {
            probeJob?.cancel()
            dripJob?.cancel()
            pendingContinuation = null
            seen.clear()
            _messages.value = emptyList()
            _isAvailable.value = false
            _isLoading.value = false
        }
    }

    private fun probe(videoId: String) {
        probeJob =
            scope.launch(dispatcher) {
                val seed = repository.initialContinuation(videoId)
                if (this@LiveChatController.videoId != videoId) return@launch
                if (seed == null) {
                    _isAvailable.value = false
                    _isLoading.value = false
                    return@launch
                }
                _isAvailable.value = true
                _isLoading.value = false
                pendingContinuation = seed
                if (panelVisible) startDrip(videoId)
            }
    }

    fun stop() {
        probeJob?.cancel()
        probeJob = null
        dripJob?.cancel()
        dripJob = null
        videoId = null
        channelId = null
        ownHandle = null
        ownName = null
        pendingContinuation = null
    }

    /** Posts one message; the result callback always fires, so the composer never hangs. */
    fun sendMessage(
        text: String,
        onResult: (Boolean) -> Unit,
    ) {
        val vid = videoId
        val ch = channelId
        if (vid == null || ch.isNullOrBlank()) {
            ShivLog.i("liveChatSend refused no-video-or-channel")
            onResult(false)
            return
        }
        scope.launch(dispatcher) {
            val status = repository.sendLiveChatMessage(vid, ch, text)
            ShivLog.i("liveChatSend video=${vid.take(8)} status=$status")
            // ATTESTATION counts as sent: report __26__0 proved both attestation
            // sends still posted, so failing the composer on it lies to the user.
            onResult(status != LiveChatSendStatus.FAILED)
        }
    }

    /** Deletes one own message; the result callback always fires. */
    fun deleteMessage(
        message: LiveChatMessage,
        onResult: (Boolean) -> Unit,
    ) {
        scope.launch(dispatcher) {
            val ok = repository.deleteLiveChatMessage(message)
            if (ok) {
                _messages.update { current -> current.filter { it.id != message.id } }
            }
            ShivLog.i("liveChatDelete id=${message.id} status=${if (ok) "DELETED" else "FAILED"}")
            onResult(ok)
        }
    }

    fun setPanelVisible(visible: Boolean) {
        if (panelVisible == visible) return
        panelVisible = visible
        if (!visible) {
            dripJob?.cancel()
            dripJob = null
            return
        }
        videoId?.takeIf { _isAvailable.value }?.let(::startDrip)
    }

    private fun startDrip(videoId: String) {
        if (dripJob?.isActive == true) return

        dripJob =
            scope.launch(dispatcher) {
                var continuation = pendingContinuation ?: repository.initialContinuation(videoId)
                pendingContinuation = null
                var consecutiveFailures = 0
                var isInitialPage = true
                while (isActive && continuation != null && this@LiveChatController.videoId == videoId) {
                    val page = repository.poll(continuation)
                    if (page == null) {
                        consecutiveFailures++
                        if (consecutiveFailures >= MAX_FAILURES) break
                        delay(RETRY_MS)
                        continue
                    }
                    consecutiveFailures = 0

                    val fresh = page.messages.filter { seen.add(it.id) }
                    val visibleFresh =
                        if (isInitialPage) {
                            isInitialPage = false
                            fresh.takeLast(INITIAL_BACKFILL_MESSAGES)
                        } else {
                            fresh
                        }
                    while (seen.size > MAX_SEEN_IDS) {
                        val it = seen.iterator()
                        if (it.hasNext()) {
                            it.next()
                            it.remove()
                        } else {
                            break
                        }
                    }
                    continuation = page.nextContinuation

                    if (visibleFresh.isEmpty()) {
                        delay(page.timeoutMs)
                    } else {
                        val interval =
                            (page.timeoutMs / visibleFresh.size)
                                .coerceIn(MIN_DRIP_MS, MAX_DRIP_MS)
                        var consumed = 0L
                        for (msg in visibleFresh) {
                            if (!isActive || this@LiveChatController.videoId != videoId) break
                            append(msg)
                            delay(interval)
                            consumed += interval
                        }
                        if (consumed < page.timeoutMs) delay(page.timeoutMs - consumed)
                    }
                }
            }
    }

    private fun append(message: LiveChatMessage) {
        _messages.update { current ->
            val combined = current + message.markMine()
            if (combined.size > MAX_MESSAGES) combined.takeLast(MAX_MESSAGES) else combined
        }
    }

    /**
     * Own-message gate: the poll renders authors by handle, but a display
     * name is possible, so a normalised match against either the saved
     * handle or the saved display name counts. Unknown identity fails
     * closed: no Remove.
     */
    private fun LiveChatMessage.markMine(): LiveChatMessage {
        val authorNorm = author.trim().trimStart('@').lowercase()
        val mine =
            ownHandle?.trim()?.trimStart('@')?.lowercase() == authorNorm ||
                ownName?.trim()?.lowercase() == authorNorm
        return if (mine == isMine) this else copy(isMine = mine)
    }

    private companion object {
        const val MAX_MESSAGES = 200
        const val MAX_SEEN_IDS = 1500
        const val RETRY_MS = 3000L
        const val MAX_FAILURES = 6
        const val INITIAL_BACKFILL_MESSAGES = 12
        const val MIN_DRIP_MS = 90L
        const val MAX_DRIP_MS = 250L
    }
}
