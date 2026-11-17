package io.github.shivytyahoo.glidetube.ui.screens.update

import android.widget.Toast
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
import io.github.shivytyahoo.glidetube.BuildConfig
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.data.update.UpdateAnnouncement
import io.github.shivytyahoo.glidetube.data.update.UpdateRepository
import io.github.shivytyahoo.glidetube.ui.startup.LaunchPrompts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Runs the launch update check once per activity and asks for the update page when it finds one. */
@HiltViewModel
class UpdateLaunchViewModel
    @Inject
    constructor(
        private val updates: UpdateRepository,
        private val prompts: LaunchPrompts,
    ) : ViewModel() {
        private var checked = false
        private val _openUpdate = MutableStateFlow(false)
        val openUpdate: StateFlow<Boolean> = _openUpdate.asStateFlow()
        private val _foundVersion = MutableStateFlow<String?>(null)
        val foundVersion: StateFlow<String?> = _foundVersion.asStateFlow()

        fun check() {
            if (checked) return
            checked = true
            if (BuildConfig.DEBUG || !BuildConfig.UPDATER_ENABLED) {
                prompts.updateCheckFinished(shownUpdate = false)
                return
            }
            viewModelScope.launch {
                val release = updates.releaseToAnnounce(UpdateAnnouncement.LAUNCH_PAGE, bypassCooldown = true)
                prompts.updateCheckFinished(shownUpdate = release != null)
                if (release != null) {
                    _foundVersion.value = release.version
                    _openUpdate.value = true
                }
            }
        }

        fun consumeOpenUpdate() {
            _openUpdate.value = false
        }
    }

/** Checks for an update once onboarding is done, and opens the update page when there is one to show. */
@Composable
fun UpdateLaunchEffect(
    needsOnboarding: Boolean?,
    onOpenUpdate: () -> Unit,
) {
    val activity = LocalContext.current as? ComponentActivity ?: return
    val viewModel: UpdateLaunchViewModel = hiltViewModel(activity)
    val openUpdate by viewModel.openUpdate.collectAsStateWithLifecycle()
    val foundVersion by viewModel.foundVersion.collectAsStateWithLifecycle()
    LaunchedEffect(needsOnboarding) { if (needsOnboarding == false) viewModel.check() }
    LaunchedEffect(openUpdate) {
        if (openUpdate) {
            viewModel.consumeOpenUpdate()
            foundVersion?.let { version ->
                Toast.makeText(
                    activity,
                    activity.getString(R.string.notification_update_available, version),
                    Toast.LENGTH_LONG,
                ).show()
            }
            onOpenUpdate()
        }
    }
}
