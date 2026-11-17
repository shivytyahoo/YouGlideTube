package io.github.shivytyahoo.glidetube.util

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.shivytyahoo.glidetube.R

/** A launcher alias: its manifest suffix, the name the picker shows and the adaptive icon it installs. */
data class LauncherIcon(
    val suffix: String,
    @StringRes val nameRes: Int,
    @DrawableRes val iconRes: Int,
)

object AppIcons {
    /** Component name prefix shared by every alias (the application id namespace). */
    const val NAMESPACE = "io.github.shivytyahoo.glidetube"

    /** The alias enabled by default in the manifest. Used as a safe fallback. */
    const val DEFAULT_SUFFIX = ".IconYouGlideTubeRed"

    /**
     * Every launcher alias, in manifest declaration order. These names identify Android launcher
     * components; changing them resets any previously selected custom icon to the enabled default.
     */
    val ALL: List<LauncherIcon> =
        listOf(
            LauncherIcon(".IconYouGlideTubeRed", R.string.icon_name_glide_red, R.mipmap.ic_launcher),
            LauncherIcon(".IconYouGlideTubeLight", R.string.icon_name_glide_light, R.mipmap.ic_launcher_glidetube_light),
            LauncherIcon(".IconYouGlideTubePlay", R.string.icon_name_glide_play, R.mipmap.ic_launcher_glidetube_play),
            LauncherIcon(".IconAmoled", R.string.icon_name_amoled, R.mipmap.ic_launcher_amoled),
            LauncherIcon(".IconMonochrome", R.string.icon_name_monochrome, R.mipmap.ic_launcher_monochrome),
            LauncherIcon(".IconGhost", R.string.icon_name_ghost, R.mipmap.ic_launcher_ghost),
            LauncherIcon(".IconDynamic", R.string.icon_name_dynamic, R.mipmap.ic_launcher_dynamic),
            LauncherIcon(".IconMaterialSky", R.string.icon_name_material_sky, R.mipmap.ic_launcher_material_sky),
            LauncherIcon(".IconMaterialMint", R.string.icon_name_material_mint, R.mipmap.ic_launcher_material_mint),
            LauncherIcon(".IconExpressiveScallop", R.string.icon_name_expressive_scallop, R.mipmap.ic_launcher_expressive_scallop),
            LauncherIcon(".IconExpressiveSegmented", R.string.icon_name_expressive_segmented, R.mipmap.ic_launcher_expressive_segmented),
            LauncherIcon(".IconExpressivePlay", R.string.icon_name_expressive_play, R.mipmap.ic_launcher_expressive_play),
            LauncherIcon(".IconExpressiveSky", R.string.icon_name_expressive_sky, R.mipmap.ic_launcher_expressive_sky),
            LauncherIcon(".IconExpressiveMint", R.string.icon_name_expressive_mint, R.mipmap.ic_launcher_expressive_mint),
            LauncherIcon(".IconExpressiveCookie", R.string.icon_name_expressive_cookie, R.mipmap.ic_launcher_expressive_cookie),
            LauncherIcon(".IconExpressiveOval", R.string.icon_name_expressive_oval, R.mipmap.ic_launcher_expressive_oval),
            LauncherIcon(".IconExpressivePill", R.string.icon_name_expressive_pill, R.mipmap.ic_launcher_expressive_pill),
        )

    val ALL_SUFFIXES: List<String> = ALL.map { it.suffix }

    fun icon(suffix: String): LauncherIcon = ALL.firstOrNull { it.suffix == suffix } ?: ALL.first { it.suffix == DEFAULT_SUFFIX }
}
