package io.github.shivytyahoo.glidetube.ui.screens.settings.region

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.shivytyahoo.glidetube.R
import io.github.shivytyahoo.glidetube.data.local.CONTENT_LANGUAGE_FOLLOW_APP
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingsPage
import io.github.shivytyahoo.glidetube.ui.components.settings.nav
import io.github.shivytyahoo.glidetube.ui.components.settings.switch
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideChoice
import io.github.shivytyahoo.glidetube.ui.components.shared.GlideChoiceDialog
import io.github.shivytyahoo.glidetube.ui.screens.settings.index.LanguageRegionIndex
import io.github.shivytyahoo.glidetube.utils.AppLanguageManager
import io.github.shivytyahoo.glidetube.utils.RegionCatalog

private enum class LanguageRegionDialog { APP_LANGUAGE, CONTENT_LANGUAGE, CONTENT_COUNTRY }

/** The UI language, and the language and country YouTube serves content for. */
@Composable
internal fun LanguageRegionScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    viewModel: LanguageRegionViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val contentLanguage by viewModel.contentLanguage.collectAsStateWithLifecycle()
    val contentCountry by viewModel.contentCountry.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<LanguageRegionDialog?>(null) }

    val systemDefault = stringResource(R.string.settings_language_system_default)
    val followApp = stringResource(R.string.music_content_language_follow_app)
    val appLanguages = remember { AppLanguageManager.getSupportedLanguages() }
    val normalizedAppLanguage = AppLanguageManager.normalizeLanguageTag(appLanguage)
    val appLanguageLabel =
        if (normalizedAppLanguage == AppLanguageManager.SYSTEM_DEFAULT) {
            systemDefault
        } else {
            appLanguages.firstOrNull { it.tag == normalizedAppLanguage }?.nativeName
                ?: AppLanguageManager.getLanguageLabel(normalizedAppLanguage)
        }
    val contentLanguageLabel =
        viewModel.contentLanguages.firstOrNull { it.tag == contentLanguage }?.nativeName ?: followApp
    val countryLabel = remember(contentCountry) { RegionCatalog.displayName(contentCountry) }

    SettingsPage(
        title = stringResource(R.string.settings_language_region_title),
        onBack = onBack,
        highlight = highlight,
    ) {
        group(key = "language_region.languages") {
            nav(
                LanguageRegionIndex.appLanguage,
                value = appLanguageLabel,
                icon = Icons.Outlined.Language,
                showChevron = false,
                onClick = { dialog = LanguageRegionDialog.APP_LANGUAGE },
            )
            nav(
                LanguageRegionIndex.contentLanguage,
                value = contentLanguageLabel,
                icon = Icons.Outlined.Translate,
                showChevron = false,
                onClick = { dialog = LanguageRegionDialog.CONTENT_LANGUAGE },
            )
            nav(
                LanguageRegionIndex.contentCountry,
                value = countryLabel,
                icon = Icons.Outlined.Public,
                showChevron = false,
                onClick = { dialog = LanguageRegionDialog.CONTENT_COUNTRY },
            )
            switch(
                LanguageRegionIndex.originalTitles,
                state = viewModel.originalTitles,
                onCheckedChange = viewModel::setOriginalTitles,
                icon = Icons.Outlined.Title,
            )
        }
    }

    when (dialog) {
        LanguageRegionDialog.APP_LANGUAGE -> {
            GlideChoiceDialog(
                title = stringResource(R.string.settings_language_dialog_title),
                options =
                    listOf(
                        GlideChoice(
                            AppLanguageManager.SYSTEM_DEFAULT,
                            systemDefault,
                            stringResource(R.string.settings_item_app_language_subtitle),
                        ),
                    ) +
                        appLanguages.map { option ->
                            GlideChoice(option.tag, option.nativeName, option.localizedName.takeIf { it != option.nativeName })
                        },
                selected = normalizedAppLanguage,
                onSelect = { tag ->
                    viewModel.setAppLanguage(tag).invokeOnCompletion {
                        if (!AppLanguageManager.systemStoresLanguage) AppLanguageManager.activityContext(context)?.recreate()
                    }
                },
                onDismiss = { dialog = null },
            )
        }

        LanguageRegionDialog.CONTENT_LANGUAGE -> {
            GlideChoiceDialog(
                title = stringResource(R.string.music_content_language_dialog_title),
                options =
                    listOf(GlideChoice(CONTENT_LANGUAGE_FOLLOW_APP, followApp)) +
                        viewModel.contentLanguages.map { GlideChoice(it.tag, it.nativeName, it.localizedName) },
                selected = contentLanguage,
                onSelect = viewModel::setContentLanguage,
                onDismiss = { dialog = null },
            )
        }

        LanguageRegionDialog.CONTENT_COUNTRY -> {
            GlideChoiceDialog(
                title = stringResource(R.string.settings_region_dialog_title),
                options = remember { RegionCatalog.sorted().map { (code, name) -> GlideChoice(code, name) } },
                selected = contentCountry,
                onSelect = viewModel::setContentCountry,
                onDismiss = { dialog = null },
            )
        }

        null -> {
            Unit
        }
    }
}
