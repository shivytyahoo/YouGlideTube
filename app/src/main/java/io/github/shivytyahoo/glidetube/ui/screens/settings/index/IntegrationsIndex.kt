package io.github.shivytyahoo.glidetube.ui.screens.settings.index

import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingEntry
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingsDestination

internal object IntegrationsIndex {
    private val page = SettingsDestination.INTEGRATIONS

    val sponsorBlock =
        SettingEntry(
            key = "integrations.sponsorblock",
            title = R.string.player_settings_sponsorblock,
            summary = R.string.player_settings_sponsorblock_subtitle,
            keywords = R.string.settings_keywords_sponsorblock,
            destination = page,
        )
    val segments =
        SettingEntry(
            key = "integrations.sponsorblock.segments",
            title = R.string.sb_segments_header,
            keywords = R.string.settings_keywords_sponsorblock,
            revealVia = sponsorBlock.key,
            destination = page,
        )
    val contribute =
        SettingEntry(
            key = "integrations.sponsorblock.contribute",
            title = R.string.sb_contribute_toggle_title,
            summary = R.string.sb_contribute_toggle_subtitle,
            section = R.string.player_settings_sponsorblock,
            revealVia = sponsorBlock.key,
            destination = page,
        )
    val userId =
        SettingEntry(
            key = "integrations.sponsorblock.user_id",
            title = R.string.sb_user_id_title,
            section = R.string.player_settings_sponsorblock,
            revealVia = sponsorBlock.key,
            destination = page,
        )
    val deArrow =
        SettingEntry(
            key = "integrations.dearrow",
            title = R.string.player_settings_dearrow,
            summary = R.string.player_settings_dearrow_subtitle,
            keywords = R.string.settings_keywords_dearrow,
            destination = page,
        )
    val deArrowRandomFrame =
        SettingEntry(
            key = "integrations.dearrow.random_frame",
            title = R.string.dearrow_random_frame,
            summary = R.string.dearrow_random_frame_subtitle,
            section = R.string.player_settings_dearrow,
            destination = page,
        )
    val deArrowBadge =
        SettingEntry(
            key = "integrations.dearrow.badge",
            title = R.string.dearrow_badge_toggle,
            summary = R.string.dearrow_badge_toggle_subtitle,
            section = R.string.player_settings_dearrow,
            destination = page,
        )
    val dislikes =
        SettingEntry(
            key = "integrations.dislikes",
            title = R.string.player_settings_rytd_title,
            summary = R.string.player_settings_rytd_subtitle,
            destination = page,
        )
    val all =
        listOf(
            sponsorBlock,
            segments,
            contribute,
            userId,
            deArrow,
            deArrowRandomFrame,
            deArrowBadge,
            dislikes,
        )
}
