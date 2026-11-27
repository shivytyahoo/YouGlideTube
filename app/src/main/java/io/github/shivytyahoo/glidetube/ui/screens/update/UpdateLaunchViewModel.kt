package io.github.shivytyahoo.glidetube.ui.screens.update

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.shivytyahoo.glidetube.BuildConfig
import io.github.shivytyahoo.glidetube.data.update.AppRelease
import io.github.shivytyahoo.glidetube.data.update.UpdateAnnouncement
import io.github.shivytyahoo.glidetube.data.update.UpdateDownload
import io.github.shivytyahoo.glidetube.data.update.UpdateRepository
import io.github.shivytyahoo.glidetube.ui.startup.LaunchPrompts
import io.github.shivytyahoo.glidetube.updater.AppUpdateInstaller
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Runs the launch update check on every app open and shows the update dialog when it finds one.
 * The check is not throttled: a warm return counts as an open, and a release that dropped since
 * the last check must be offered right away — the in-flight guard below is only there so the
 * cold-start double call (composition + ON_START) fetches once instead of twice.
 */
@HiltViewModel
class UpdateLaunchViewModel
    @Inject
    constructor(
        private val updates: UpdateRepository,
        private val installer: AppUpdateInstaller,
        private val prompts: LaunchPrompts,
    ) : ViewModel() {
        private var checkJob: Job? = null
        private val _promptRelease = MutableStateFlow<AppRelease?>(null)
        val promptRelease: StateFlow<AppRelease?> = _promptRelease.asStateFlow()

        fun check() {
            if (checkJob?.isActive == true) return
            if (BuildConfig.DEBUG || !BuildConfig.UPDATER_ENABLED) {
                prompts.updateCheckFinished(shownUpdate = false)
                return
            }
            checkJob =
                viewModelScope.launch {
                    val release = updates.releaseToAnnounce(UpdateAnnouncement.LAUNCH_PAGE, bypassCooldown = true)
                    val shown = release != null && !isDownloadActive(release)
                    prompts.updateCheckFinished(shownUpdate = shown)
                    if (shown) _promptRelease.value = release
                }
        }

        /** Starts the download for the release on screen, unless it is already running or ready, then dismisses. */
        fun updateNow() {
            val release = _promptRelease.value ?: return
            _promptRelease.value = null
            viewModelScope.launch {
                when (installer.state(release.version).first()) {
                    is UpdateDownload.Idle, is UpdateDownload.Failed -> installer.download(release)
                    else -> Unit
                }
            }
        }

        /** Skips this version everywhere: the launch prompt never offers it again. */
        fun skipPrompt() {
            val release = _promptRelease.value ?: return
            _promptRelease.value = null
            viewModelScope.launch { updates.skip(release.version) }
        }

        /** "Later": dismisses for this launch only. The next open checks again. */
        fun dismissPrompt() {
            _promptRelease.value = null
        }

        private suspend fun isDownloadActive(release: AppRelease): Boolean =
            when (installer.state(release.version).first()) {
                is UpdateDownload.Running, UpdateDownload.Verifying -> true
                else -> false
            }
    }

/** Checks for an update once onboarding is done, and shows the update dialog when there is one. */
@Composable
fun UpdateLaunchEffect(
    needsOnboarding: Boolean?,
    onOpenUpdate: () -> Unit,
) {
    val activity = LocalContext.current as? ComponentActivity ?: return
    val viewModel: UpdateLaunchViewModel = hiltViewModel(activity)
    val promptRelease by viewModel.promptRelease.collectAsStateWithLifecycle()
    val needsOnboardingState = rememberUpdatedState(needsOnboarding)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(needsOnboarding) { if (needsOnboarding == false) viewModel.check() }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START && needsOnboardingState.value == false) viewModel.check()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // The post-update notes effect lives here, at the same app-level composition point,
    // so the launch flow stays in one place.
    WhatsNewEffect()
    promptRelease?.let { release ->
        UpdatePromptDialog(
            release = release,
            onUpdateNow = {
                viewModel.updateNow()
                onOpenUpdate()
            },
            onSkip = { viewModel.skipPrompt() },
            onLater = { viewModel.dismissPrompt() },
        )
    }
}
