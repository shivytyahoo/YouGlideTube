package io.github.aedev.flow.ui.tv.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.HomeFeedColumns
import io.github.aedev.flow.data.local.availableWith
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.data.model.Playlist
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.innertube.pages.MoodAndGenres
import io.github.aedev.flow.ui.components.search.SearchSuggestionsPanel
import io.github.aedev.flow.ui.components.shared.FocusLiftDefaults
import io.github.aedev.flow.ui.components.shared.FlowConnectedToggleGroup
import io.github.aedev.flow.ui.components.shared.focusLift
import io.github.aedev.flow.ui.components.shared.FlowSearchField
import io.github.aedev.flow.ui.components.shared.FlowToggleOption
import io.github.aedev.flow.ui.components.shared.MediaKind
import io.github.aedev.flow.ui.components.shared.quickactions.sharedQuickActionsViewModel
import io.github.aedev.flow.ui.screens.music.MusicSearchBody
import io.github.aedev.flow.ui.screens.music.MusicSearchViewModel
import io.github.aedev.flow.ui.screens.search.SearchResultsBody
import io.github.aedev.flow.ui.screens.search.SearchViewModel
import io.github.aedev.flow.ui.screens.search.rememberSearchState
import io.github.aedev.flow.ui.screens.search.rememberSearchSubmitter
import io.github.aedev.flow.ui.tv.components.TvIconButton
import io.github.aedev.flow.ui.tv.components.TvScreenFrame
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop

private val KindSelectorWidth = 320.dp

/**
 * TV search: the query, voice search and the Videos and Music switch across the top, the phone's
 * suggestions and results under them. OK on the field raises the system keyboard, which types in
 * every language. Both kinds read the one query, so switching kind keeps what was typed.
 */
@Composable
fun TvSearchScreen(
    viewModel: SearchViewModel,
    onVideoClick: (Video) -> Unit,
    onChannelClick: (Channel) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onPlayTrack: (MusicTrack, List<MusicTrack>, String?) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenMusicPlaylist: (String) -> Unit,
    onOpenMood: (MoodAndGenres.Item) -> Unit,
    onOpenMoods: () -> Unit,
    modifier: Modifier = Modifier,
    musicViewModel: MusicSearchViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val dimens = LocalTvDimens.current
    val softwareKeyboard = LocalSoftwareKeyboardController.current
    val uiState by viewModel.uiState.collectAsState()
    val state = rememberSearchState(viewModel)
    val quickActions = sharedQuickActionsViewModel()
    val submitVideo = rememberSearchSubmitter(viewModel, state, quickActions)
    val pagingItems = viewModel.searchResults.collectAsLazyPagingItems()
    val gridState = rememberLazyGridState()
    var kind by rememberSaveable { mutableStateOf(MediaKind.Videos) }
    val text = state.textFieldState
    val kinds = MediaKind.entries.map { FlowToggleOption(it, stringResource(it.labelRes), it.icon) }

    fun submit() {
        softwareKeyboard?.hide()
        val query = text.text.toString()
        if (kind == MediaKind.Music) musicViewModel.performSearch(query) else submitVideo(query, uiState.filters)
    }

    // The phone's search page focuses its field on entry and marks typing when the field
    // takes focus; this page never wired either, so on TV the remote's focus started on
    // the rail, the field never asked the system for the keyboard, and the suggestions
    // state never flipped. Wire both, and raise the keyboard as the screen opens — on a
    // TV the keyboard is how you type, so it should be there waiting.
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { fieldFocus.requestFocus() }
        delay(250)
        runCatching { softwareKeyboard?.show() }
    }

    LaunchedEffect(state) {
        snapshotFlow { text.text.toString() }
            .drop(1)
            .collect(musicViewModel::onQueryChange)
    }

    // Voice search runs the platform recognizer in-process. The activity route this used
    // before opens the TV's "Speak to search" overlay, which on this TV takes focus,
    // shows nothing typeable and hears nothing — while the same TV's recognizer service
    // works fine for every other app. Listening state lives on the mic button itself.
    var listening by remember { mutableStateOf(false) }
    val recognizerHolder = remember { arrayOfNulls<SpeechRecognizer>(1) }
    DisposableEffect(Unit) {
        onDispose {
            recognizerHolder[0]?.destroy()
            recognizerHolder[0] = null
        }
    }

    fun beginListening() {
        runCatching { recognizerHolder[0]?.destroy() }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizerHolder[0] = recognizer
        recognizer.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    listening = false
                }

                override fun onError(error: Int) {
                    listening = false
                    if (
                        error != SpeechRecognizer.ERROR_NO_MATCH &&
                        error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ) {
                        Toast
                            .makeText(context, R.string.voice_search_unavailable_tv, Toast.LENGTH_LONG)
                            .show()
                    }
                }

                override fun onResults(results: Bundle?) {
                    listening = false
                    results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.takeIf(String::isNotBlank)
                        ?.let { spoken ->
                            text.setTextAndPlaceCursorAtEnd(spoken)
                            submit()
                        }
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            },
        )
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
        listening = true
        runCatching { recognizer.startListening(intent) }
            .onFailure { listening = false }
    }

    val micPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                beginListening()
            } else {
                Toast
                    .makeText(context, R.string.voice_search_unavailable_tv, Toast.LENGTH_LONG)
                    .show()
            }
        }

    fun onMicClick() {
        when {
            listening -> {
                runCatching { recognizerHolder[0]?.stopListening() }
                listening = false
            }
            !SpeechRecognizer.isRecognitionAvailable(context) -> {
                Toast
                    .makeText(context, R.string.voice_search_unavailable_tv, Toast.LENGTH_LONG)
                    .show()
                runCatching { fieldFocus.requestFocus() }
            }
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> beginListening()
            else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    TvScreenFrame(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = SearchBarHorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlowSearchField(
                    state = text,
                    placeholder = stringResource(R.string.search_videos_channels_placeholder),
                    onSearch = { submit() },
                    modifier =
                        Modifier
                            .weight(1f)
                            .onPreviewKeyEvent { event ->
                                // Focus alone must not reopen typing on TV: the shell hands
                                // focus back to this field whenever the content under it
                                // loses focus (a submitted search included), and reading that
                                // as "start typing" bounced every attempt to open a result
                                // straight back to the keyboard. OK on the field is the
                                // deliberate way back into typing.
                                if (
                                    event.key == Key.DirectionCenter &&
                                    event.type == KeyEventType.KeyUp &&
                                    !state.isTyping
                                ) {
                                    state.startTyping()
                                    true
                                } else {
                                    false
                                }
                            },
                    focusRequester = fieldFocus,
                )
                TvIconButton(
                    icon = Icons.Rounded.Mic,
                    contentDescription = stringResource(R.string.voice_search_cd),
                    active = listening,
                    onClick = { onMicClick() },
                )
                FlowConnectedToggleGroup(
                    options = kinds,
                    selected = kind,
                    onSelected = { kind = it },
                    modifier = Modifier.width(KindSelectorWidth),
                )
            }
            // Typing on a TV cannot rest on the system keyboard: some TVs never raise it for
            // a remote-focused field (this one included — two builds of focus fixes did not
            // move it). While typing, the left half is the app's own letter grid, driven
            // entirely by the remote; the system keyboard, where a TV does raise it, is a
            // bonus on top, never the only way in.
            val typing = uiState.query.isBlank() || state.isTyping
            if (kind == MediaKind.Music) {
                if (typing) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(start = 16.dp, top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        TvSearchKeyboard(
                            onKey = { ch -> text.setTextAndPlaceCursorAtEnd(text.text.toString() + ch) },
                            onSpace = { text.setTextAndPlaceCursorAtEnd(text.text.toString() + " ") },
                            onBackspace = {
                                val current = text.text.toString()
                                if (current.isNotEmpty()) {
                                    text.setTextAndPlaceCursorAtEnd(current.dropLast(1))
                                }
                            },
                            onClear = { text.clearText() },
                            onSubmit = { submit() },
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            MusicSearchBody(
                                viewModel = musicViewModel,
                                onTrackClick = onPlayTrack,
                                onAlbumClick = onOpenAlbum,
                                onArtistClick = onOpenArtist,
                                onPlaylistClick = onOpenMusicPlaylist,
                                onMoodClick = onOpenMood,
                                onMoodsSeeAll = onOpenMoods,
                                dismissSearchInput = {},
                                onSuggestionPicked = {},
                            )
                        }
                    }
                } else {
                    MusicSearchBody(
                        viewModel = musicViewModel,
                        onTrackClick = onPlayTrack,
                        onAlbumClick = onOpenAlbum,
                        onArtistClick = onOpenArtist,
                        onPlaylistClick = onOpenMusicPlaylist,
                        onMoodClick = onOpenMood,
                        onMoodsSeeAll = onOpenMoods,
                        dismissSearchInput = {},
                        onSuggestionPicked = {},
                    )
                }
            } else if (typing) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(start = 16.dp, top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    TvSearchKeyboard(
                        onKey = { ch -> text.setTextAndPlaceCursorAtEnd(text.text.toString() + ch) },
                        onSpace = { text.setTextAndPlaceCursorAtEnd(text.text.toString() + " ") },
                        onBackspace = {
                            val current = text.text.toString()
                            if (current.isNotEmpty()) {
                                text.setTextAndPlaceCursorAtEnd(current.dropLast(1))
                            }
                        },
                        onClear = { text.clearText() },
                        onSubmit = { submit() },
                    )
                    SearchSuggestionsPanel(
                        query = state.query,
                        history = state.matchingHistory,
                        suggestions = state.suggestions,
                        onSubmit = { suggestion ->
                            text.setTextAndPlaceCursorAtEnd(suggestion)
                            submitVideo(suggestion, uiState.filters)
                        },
                        onHistorySelect = { item ->
                            text.setTextAndPlaceCursorAtEnd(item.query)
                            submitVideo(item.query, item.filters?.availableWith(false) ?: uiState.filters)
                        },
                        onFill = text::setTextAndPlaceCursorAtEnd,
                        onDeleteHistoryItem = state::deleteHistoryItem,
                        onClearHistory = state::clearHistory,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                SearchResultsBody(
                    viewModel = viewModel,
                    filters = uiState.filters,
                    shortsEnabled = false,
                    isGridMode = false,
                    feedColumns = HomeFeedColumns.AUTO,
                    pagingItems = pagingItems,
                    gridState = gridState,
                    quickActions = quickActions,
                    onVideoClick = onVideoClick,
                    onChannelClick = onChannelClick,
                    onPlaylistClick = onPlaylistClick,
                    onShortsQueue = {},
                    dismissKeyboard = state::stopTyping,
                    maxAutoColumns = dimens.feedMaxColumns,
                    minCellWidth = dimens.feedCardMinWidth,
                )
            }
        }
    }
}

/** The phone pages under the bar keep a 16 dp margin; the bar lines up with them. */
private val SearchBarHorizontalPadding = 16.dp

private val SearchKeyWidth = 60.dp
private val SearchKeyHeight = 46.dp
private val SearchKeySpacing = 8.dp

/**
 * The TV search keyboard: letters and digits in a remote-driven grid, with space, delete,
 * clear and search under them. It answers to the D-pad alone, so typing never depends on
 * the TV raising a system keyboard — the failure this screen kept shipping.
 */
@Composable
private fun TvSearchKeyboard(
    onKey: (Char) -> Unit,
    onSpace: () -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".chunked(6)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(SearchKeySpacing),
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(SearchKeySpacing)) {
                row.forEach { ch ->
                    TvSearchKey(
                        label = ch.toString(),
                        modifier = Modifier.width(SearchKeyWidth),
                        onClick = { onKey(ch) },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(SearchKeySpacing)) {
            TvSearchKey(
                label = stringResource(R.string.search_key_space),
                modifier = Modifier.width(SearchKeyWidth * 2 + SearchKeySpacing),
                onClick = onSpace,
            )
            TvSearchKey(
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Backspace,
                        contentDescription = stringResource(R.string.search_key_delete),
                        modifier = Modifier.size(22.dp),
                    )
                },
                modifier = Modifier.width(SearchKeyWidth * 2 + SearchKeySpacing),
                onClick = onBackspace,
            )
            TvSearchKey(
                label = stringResource(R.string.clear),
                modifier = Modifier.width(SearchKeyWidth * 2 + SearchKeySpacing),
                onClick = onClear,
            )
        }
        TvSearchKey(
            label = stringResource(R.string.search),
            modifier = Modifier.width(SearchKeyWidth * 6 + SearchKeySpacing * 5),
            onClick = onSubmit,
        )
    }
}

/** One key of the TV search keyboard: a focusable surface in the TV buttons' own style. */
@Composable
private fun TvSearchKey(
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = modifier.focusLift(interactionSource, FocusLiftDefaults.SMALL_SCALE),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        interactionSource = interactionSource,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(SearchKeyHeight),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                icon()
            } else {
                Text(text = label.orEmpty(), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
