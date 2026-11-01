package io.github.aedev.flow.ui.screens.recognition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.recognition.MusicRecognitionRepository
import io.github.aedev.flow.data.recognition.RecognitionResult
import io.github.aedev.flow.data.recognition.RecognitionStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecognitionViewModel
    @Inject
    constructor(
        private val recognitionRepository: MusicRecognitionRepository,
    ) : ViewModel() {
        val status: StateFlow<RecognitionStatus> = recognitionRepository.status

        private var recognitionJob: Job? = null

        fun hasRecordPermission(): Boolean = recognitionRepository.hasRecordPermission()

        fun startRecognition() {
            if (recognitionJob?.isActive == true) return
            recognitionJob = viewModelScope.launch { recognitionRepository.recognize() }
        }

        fun cancel() {
            recognitionJob?.cancel()
            recognitionJob = null
            recognitionRepository.reset()
        }

        /** On screen entry, clear a previous result but never interrupt a running recognition. */
        fun clearResultOnEnter() {
            when (status.value) {
                is RecognitionStatus.Success,
                is RecognitionStatus.NoMatch,
                is RecognitionStatus.Error,
                -> recognitionRepository.reset()

                else -> Unit
            }
        }

        companion object {
            fun searchQueryFor(
                title: String,
                artist: String,
            ): String = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")

            /** Build a playable track from a recognized result that carries a YouTube id. */
            fun toMusicTrack(result: RecognitionResult): MusicTrack? {
                val videoId = result.youtubeVideoId?.takeIf { it.isNotBlank() } ?: return null
                return MusicTrack(
                    videoId = videoId,
                    title = result.title,
                    artist = result.artist,
                    thumbnailUrl = result.coverArtHqUrl ?: result.coverArtUrl ?: "",
                    duration = 0,
                    album = result.album.orEmpty(),
                )
            }
        }
    }
