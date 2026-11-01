package io.github.aedev.flow.ui.tv.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.aedev.flow.ui.components.settings.SettingsDestination
import io.github.aedev.flow.ui.screens.settings.SettingsHost
import io.github.aedev.flow.ui.screens.settings.SettingsHostOptions
import io.github.aedev.flow.ui.screens.settings.about.DonationsScreen
import io.github.aedev.flow.ui.screens.update.UPDATE_ROUTE
import io.github.aedev.flow.ui.screens.update.UpdateScreen
import io.github.aedev.flow.ui.tv.components.TvScreenFrame
import io.github.aedev.flow.ui.tv.screens.TvRemoteGuideScreen

/** Settings, hosted from the phone with TV's own remote guide page, and the screens it opens. */
internal fun NavGraphBuilder.tvSettingsRoutes(navController: NavHostController) {
    composable(TV_SETTINGS_ROUTE) {
        val options =
            remember {
                SettingsHostOptions(
                    pages =
                        mapOf(
                            SettingsDestination.REMOTE_GUIDE to { onBack -> TvRemoteGuideScreen(onBack = onBack) },
                        ),
                )
            }
        TvScreenFrame {
            SettingsHost(
                start = null,
                onExit = { navController.popBackStack() },
                onOpenDonations = { navController.navigate(TvRoutes.DONATIONS) },
                onOpenUpdate = { navController.navigate(UPDATE_ROUTE) },
                options = options,
            )
        }
    }
    composable(TvRoutes.DONATIONS) {
        TvScreenFrame { DonationsScreen(onNavigateBack = { navController.popBackStack() }) }
    }
    composable(UPDATE_ROUTE) {
        TvScreenFrame { UpdateScreen(onClose = { navController.popBackStack() }) }
    }
}
