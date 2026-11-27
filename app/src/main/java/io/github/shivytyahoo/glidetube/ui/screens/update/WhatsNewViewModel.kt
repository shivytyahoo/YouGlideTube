package io.github.shivytyahoo.glidetube.ui.screens.update

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.shivytyahoo.glidetube.BuildConfig
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.data.local.LocalDataManager
import io.github.shivytyahoo.glidetube.data.update.NoteSection
import io.github.shivytyahoo.glidetube.data.update.NoteSpan
import io.github.shivytyahoo.glidetube.data.update.NoteText
import io.github.shivytyahoo.glidetube.data.update.ReleaseNotes
import io.github.shivytyahoo.glidetube.data.update.UpdateRepository
import io.github.shivytyahoo.glidetube.data.update.loadBundledChangelog
import io.github.shivytyahoo.glidetube.data.update.parseReleaseNotes
import io.github.shivytyahoo.glidetube.ui.startup.LaunchPrompts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Shows the "What's new" dialog once, on the first launch after an app update: the installed
 * versionCode is newer than the last one the dialog was shown for. Ordinary launches never
 * show it, and a first install does not count as an update.
 */
@HiltViewModel
class WhatsNewViewModel
    @Inject
    constructor(
        private val updates: UpdateRepository,
        private val dataManager: LocalDataManager,
        private val prompts: LaunchPrompts,
        @ApplicationContext private val appContext: Context,
    ) : ViewModel() {
        private var checked = false
        private val _whatsNew = MutableStateFlow<WhatsNewState?>(null)
        val whatsNew: StateFlow<WhatsNewState?> = _whatsNew.asStateFlow()

        fun check() {
            if (checked) return
            checked = true
            viewModelScope.launch {
                val current = BuildConfig.VERSION_CODE
                val seen = dataManager.lastSeenVersionCode.first()
                if (seen == null) {
                    dataManager.setLastSeenVersionCode(current)
                    return@launch
                }
                if (seen >= current) return@launch
                if (prompts.updatePromptResolved()) return@launch
                val version = BuildConfig.VERSION_NAME
                val notes = notesFor(version)
                _whatsNew.value = WhatsNewState(version, notes)
            }
        }

        /** Marks this version seen so the dialog never shows again for it. */
        fun dismiss() {
            _whatsNew.value = null
            viewModelScope.launch { dataManager.setLastSeenVersionCode(BuildConfig.VERSION_CODE) }
        }

        private suspend fun notesFor(version: String): ReleaseNotes {
            val bundled = loadBundledChangelog(appContext, version)
            if (bundled != null) {
                return ReleaseNotes(
                    intro = emptyList(),
                    sections =
                        listOf(
                            NoteSection(
                                version,
                                bundled.map { NoteText(listOf(NoteSpan.Plain(it))) },
                            ),
                        ),
                )
            }
            updates.latestReleaseBody()?.let { return parseReleaseNotes(it) }
            return ReleaseNotes(
                intro = listOf(NoteText(listOf(NoteSpan.Plain(appContext.getString(R.string.whats_new_unavailable))))),
                sections = emptyList(),
            )
        }
    }

data class WhatsNewState(
    val version: String,
    val notes: ReleaseNotes,
)

/** Shows the "What's new" dialog on the first launch after an update, if there is one to show. */
@Composable
fun WhatsNewEffect() {
    val activity = LocalContext.current as? ComponentActivity ?: return
    val viewModel: WhatsNewViewModel = hiltViewModel(activity)
    val whatsNew by viewModel.whatsNew.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.check() }
    whatsNew?.let { state ->
        WhatsNewDialog(
            state = state,
            onClose = { viewModel.dismiss() },
        )
    }
}
