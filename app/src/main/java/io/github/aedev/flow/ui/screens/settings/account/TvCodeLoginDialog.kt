package io.github.aedev.flow.ui.screens.settings.account

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.R
import io.github.aedev.flow.data.account.OAuthSessionManager
import io.github.aedev.flow.data.account.TvOAuth
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface TvCodeUiState {
    data object Loading : TvCodeUiState

    data class ShowCode(
        val session: TvOAuth.DeviceSession,
    ) : TvCodeUiState

    data object Success : TvCodeUiState

    data class Error(
        val error: String,
    ) : TvCodeUiState
}

@HiltViewModel
class TvCodeLoginViewModel
    @Inject
    constructor(
        private val oauthSessionManager: OAuthSessionManager,
    ) : ViewModel() {
        private val _state = MutableStateFlow<TvCodeUiState>(TvCodeUiState.Loading)
        val state = _state.asStateFlow()
        private var pollJob: Job? = null

        fun start() {
            if (pollJob?.isActive == true) return
            pollJob =
                viewModelScope.launch {
                    _state.value = TvCodeUiState.Loading
                    val session =
                        TvOAuth.requestDeviceCode().getOrElse {
                            _state.value = TvCodeUiState.Error(it.message ?: "network")
                            return@launch
                        }
                    _state.value = TvCodeUiState.ShowCode(session)
                    var intervalSeconds = session.intervalSeconds
                    val deadline = System.currentTimeMillis() + session.expiresInSeconds * 1000L
                    while (System.currentTimeMillis() < deadline) {
                        delay(intervalSeconds * 1000L)
                        when (val result = TvOAuth.pollToken(session.deviceCode)) {
                            is TvOAuth.PollResult.Success -> {
                                oauthSessionManager.onSignedIn(result.tokens)
                                _state.value = TvCodeUiState.Success
                                return@launch
                            }

                            TvOAuth.PollResult.Pending -> Unit

                            TvOAuth.PollResult.SlowDown -> intervalSeconds += 5

                            is TvOAuth.PollResult.Failed -> {
                                _state.value = TvCodeUiState.Error(result.error)
                                return@launch
                            }
                        }
                    }
                    _state.value = TvCodeUiState.Error("expired_token")
                }
        }
    }

/**
 * The TV-code sign-in dialog: a big code, the address to enter it at, and a QR that
 * opens that address. Works on phones too — the code can be approved on any device.
 */
@Composable
fun TvCodeLoginDialog(
    onDismiss: () -> Unit,
    viewModel: TvCodeLoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.start() }
    LaunchedEffect(state) {
        if (state is TvCodeUiState.Success) {
            delay(1300)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tv_code_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (val current = state) {
                    TvCodeUiState.Loading -> {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.tv_code_loading))
                    }

                    is TvCodeUiState.ShowCode -> {
                        Text(
                            stringResource(R.string.tv_code_instructions, current.session.verificationUrl),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = current.session.userCode,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = MaterialTheme.typography.displaySmall.letterSpacing,
                        )
                        QrImage(content = current.session.verificationUrl)
                        Text(
                            stringResource(R.string.tv_code_waiting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    TvCodeUiState.Success -> {
                        Text(
                            stringResource(R.string.tv_code_success),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    is TvCodeUiState.Error -> {
                        Text(
                            stringResource(
                                when (current.error) {
                                    "access_denied" -> R.string.tv_code_error_denied
                                    "expired_token" -> R.string.tv_code_error_expired
                                    else -> R.string.tv_code_error_generic
                                },
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (state is TvCodeUiState.Error) {
                TextButton(onClick = { viewModel.start() }) {
                    Text(stringResource(R.string.tv_code_retry))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun QrImage(content: String) {
    val bitmap =
        remember(content) {
            runCatching {
                val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 480, 480)
                Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565).also { bmp ->
                    for (x in 0 until matrix.width) {
                        for (y in 0 until matrix.height) {
                            bmp.setPixel(
                                x,
                                y,
                                if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE,
                            )
                        }
                    }
                }
            }.getOrNull()
        }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(180.dp),
        )
    }
}
