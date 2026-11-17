package io.github.shivytyahoo.glidetube.ui.tv.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.shivytyahoo.glidetube.ui.components.settings.SettingsDestination
import io.github.shivytyahoo.glidetube.ui.screens.settings.SettingsHost
import io.github.shivytyahoo.glidetube.ui.screens.settings.SettingsHostOptions
import io.github.shivytyahoo.glidetube.ui.screens.update.UPDATE_ROUTE
import io.github.shivytyahoo.glidetube.ui.screens.update.UpdateScreen
import io.github.shivytyahoo.glidetube.ui.tv.components.TvScreenFrame
import io.github.shivytyahoo.glidetube.ui.tv.screens.TvRemoteGuideScreen

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
                onOpenUpdate = { navController.navigate(UPDATE_ROUTE) },
                options = options,
            )
        }
    }
    composable(UPDATE_ROUTE) {
        TvScreenFrame { UpdateScreen(onClose = { navController.popBackStack() }) }
    }
}
