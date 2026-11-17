package io.github.shivytyahoo.glidetube.ui.screens.settings.integrations

import androidx.compose.runtime.Immutable
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.shivytyahoo.glidetube.data.local.PlayerPreferences
import io.github.shivytyahoo.glidetube.data.local.SponsorBlockAction
import io.github.shivytyahoo.glidetube.data.model.SponsorBlockCategories
import io.github.shivytyahoo.glidetube.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/** One segment category's behaviour and colour; a null [colorArgb] means the default colour. */
@Immutable
data class SegmentSetting(
    val action: SponsorBlockAction,
    val colorArgb: Int?,
)

@HiltViewModel
class IntegrationsViewModel
    @Inject
    constructor(
        private val preferences: PlayerPreferences,
    ) : SettingsViewModel() {
        val sponsorBlock = preferences.sponsorBlockEnabled.asState(false)
        val submitButton = preferences.sbSubmitEnabled.asState(false)
        val userId = preferences.sbUserId.asState(null)
        val deArrow = preferences.deArrowEnabled.asState(false)
        val deArrowBadge = preferences.deArrowBadgeEnabled.asState(false)
        val deArrowRandomFrame = preferences.deArrowRandomFrame.asState(true)
        val dislikes = preferences.rytdEnabled.asState(true)

        /** Every category's flows are built once here, not per recomposition. */
        val segments =
            combine(
                SponsorBlockCategories.all.map { category ->
                    combine(preferences.sbActionForCategory(category), preferences.sbColorForCategory(category)) { action, color ->
                        category to SegmentSetting(action, color)
                    }
                },
            ) { pairs -> pairs.toMap() }
                .asState(
                    SponsorBlockCategories.all.associateWith { SegmentSetting(SponsorBlockCategories.defaultAction(it), null) },
                )

        fun setSponsorBlock(value: Boolean) = write { preferences.setSponsorBlockEnabled(value) }

        fun setSegmentAction(
            category: String,
            action: SponsorBlockAction,
        ) = write { preferences.setSbActionForCategory(category, action) }

        fun setSegmentColor(
            category: String,
            colorArgb: Int?,
        ) = write { preferences.setSbColorForCategory(category, colorArgb) }

        fun setSubmitButton(value: Boolean) = write { preferences.setSbSubmitEnabled(value) }

        /** A blank id asks for a fresh one rather than clearing the identity. */
        fun setUserId(input: String) =
            write {
                preferences.setSbUserId(input.trim().ifBlank { preferences.getOrCreateSbUserId() })
            }

        fun setDeArrow(value: Boolean) = write { preferences.setDeArrowEnabled(value) }

        fun setDeArrowBadge(value: Boolean) = write { preferences.setDeArrowBadgeEnabled(value) }

        fun setDeArrowRandomFrame(value: Boolean) = write { preferences.setDeArrowRandomFrame(value) }

        fun setDislikes(value: Boolean) = write { preferences.setRytdEnabled(value) }
    }
