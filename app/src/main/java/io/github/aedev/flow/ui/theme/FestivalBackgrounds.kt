package io.github.aedev.flow.ui.theme

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import io.github.aedev.flow.R

/** One drawable shape a festival theme is built from. The renderer draws it on a Canvas. */
enum class FestivalShape {
    DIYA,
    DOT,
    PETAL,
    STAR,
    HEART,
    MOON,
    LANTERN,
    KITE,
    BALLOON,
    FLAME,
    SNOWFLAKE,
    PUMPKIN,
    BAT,
    EGG,
    LEAF,
    SHAMROCK,
    FLOWER,
    FIREWORK,
}

/** How one element's particles move across the screen. */
enum class FestivalMotion {
    /** Bottom to top, swaying as it climbs (diyas, flames, balloons, hearts). */
    RISE,

    /** Top to bottom, swaying as it falls (snow, petals, leaves, confetti). */
    FALL,

    /** Slow drift in place, bobbing on a sine (moons, lanterns, kites, eggs). */
    FLOAT,

    /** A burst that grows and fades, then restarts (fireworks). */
    BURST,
}

/** One layer of a festival theme: a shape, its palette, and how many of them drift. */
data class FestivalElement(
    val shape: FestivalShape,
    val colors: List<Color>,
    val count: Int,
    val sizeDp: Float,
    val motion: FestivalMotion,
    val durationMs: Int,
)

/**
 * The animated background a festival draws over the Morphe circles. One choice, not
 * thirty switches: Settings → Appearance → Animated backgrounds lists these, and the
 * stored [id] is the only preference. Diwali reuses the existing diya layer; every
 * other theme is drawn by the festival renderer from its [elements].
 */
enum class FestivalBackground(
    val id: String,
    @StringRes val nameRes: Int,
    val elements: List<FestivalElement>,
) {
    NONE("none", R.string.animated_background_none, emptyList()),
    DIWALI(
        "diwali",
        R.string.festival_diwali,
        listOf(FestivalElement(FestivalShape.DIYA, listOf(DiyaFlameOuter), 6, 34f, FestivalMotion.RISE, 11_000)),
    ),
    HOLI(
        "holi",
        R.string.festival_holi,
        listOf(
            FestivalElement(FestivalShape.DOT, listOf(FestivalPink, FestivalGreen, FestivalYellow, FestivalBlue), 26, 9f, FestivalMotion.FALL, 9_000),
            FestivalElement(FestivalShape.FIREWORK, listOf(FestivalPink, FestivalYellow, FestivalTeal), 3, 60f, FestivalMotion.BURST, 4_000),
        ),
    ),
    EID_FITR(
        "eid_fitr",
        R.string.festival_eid_fitr,
        listOf(
            FestivalElement(FestivalShape.MOON, listOf(FestivalYellow), 5, 30f, FestivalMotion.FLOAT, 8_000),
            FestivalElement(FestivalShape.STAR, listOf(FestivalYellow, FestivalWhite), 18, 8f, FestivalMotion.FLOAT, 6_000),
        ),
    ),
    EID_ADHA(
        "eid_adha",
        R.string.festival_eid_adha,
        listOf(
            FestivalElement(FestivalShape.LANTERN, listOf(FestivalYellow, FestivalGreen), 6, 26f, FestivalMotion.FLOAT, 9_000),
            FestivalElement(FestivalShape.MOON, listOf(FestivalYellow), 4, 26f, FestivalMotion.FLOAT, 8_000),
        ),
    ),
    CHRISTMAS(
        "christmas",
        R.string.festival_christmas,
        listOf(
            FestivalElement(FestivalShape.SNOWFLAKE, listOf(FestivalWhite), 24, 12f, FestivalMotion.FALL, 10_000),
            FestivalElement(FestivalShape.DOT, listOf(FestivalRed, FestivalGreen, FestivalYellow), 14, 7f, FestivalMotion.FLOAT, 5_000),
        ),
    ),
    NEW_YEAR(
        "new_year",
        R.string.festival_new_year,
        listOf(
            FestivalElement(FestivalShape.FIREWORK, listOf(FestivalYellow, FestivalRed, FestivalBlue, FestivalPink), 5, 70f, FestivalMotion.BURST, 3_500),
            FestivalElement(FestivalShape.DOT, listOf(FestivalYellow, FestivalWhite, FestivalPink), 20, 7f, FestivalMotion.FALL, 8_000),
        ),
    ),
    HALLOWEEN(
        "halloween",
        R.string.festival_halloween,
        listOf(
            FestivalElement(FestivalShape.PUMPKIN, listOf(FestivalOrange), 5, 30f, FestivalMotion.FLOAT, 9_000),
            FestivalElement(FestivalShape.BAT, listOf(FestivalPurple), 6, 22f, FestivalMotion.FLOAT, 7_000),
        ),
    ),
    NAVRATRI(
        "navratri",
        R.string.festival_navratri,
        listOf(
            FestivalElement(FestivalShape.DIYA, listOf(DiyaFlameOuter), 5, 30f, FestivalMotion.RISE, 11_000),
            FestivalElement(FestivalShape.DOT, listOf(FestivalPink, FestivalOrange, FestivalYellow), 18, 8f, FestivalMotion.FALL, 9_000),
        ),
    ),
    DURGA_PUJA(
        "durga_puja",
        R.string.festival_durga_puja,
        listOf(FestivalElement(FestivalShape.PETAL, listOf(FestivalOrange, FestivalYellow), 24, 12f, FestivalMotion.FALL, 9_000)),
    ),
    GANESH_CHATURTHI(
        "ganesh_chaturthi",
        R.string.festival_ganesh_chaturthi,
        listOf(
            FestivalElement(FestivalShape.FLOWER, listOf(FestivalOrange, FestivalYellow), 10, 22f, FestivalMotion.FLOAT, 8_000),
            FestivalElement(FestivalShape.DIYA, listOf(DiyaFlameOuter), 4, 28f, FestivalMotion.RISE, 11_000),
        ),
    ),
    RAKSHA_BANDHAN(
        "raksha_bandhan",
        R.string.festival_raksha_bandhan,
        listOf(FestivalElement(FestivalShape.FLOWER, listOf(FestivalRed, FestivalYellow), 12, 20f, FestivalMotion.FLOAT, 8_000)),
    ),
    JANMASHTAMI(
        "janmashtami",
        R.string.festival_janmashtami,
        listOf(FestivalElement(FestivalShape.PETAL, listOf(FestivalBlue, FestivalTeal, FestivalGreen), 22, 12f, FestivalMotion.FALL, 9_000)),
    ),
    MAKAR_SANKRANTI(
        "makar_sankranti",
        R.string.festival_makar_sankranti,
        listOf(FestivalElement(FestivalShape.KITE, listOf(FestivalPink, FestivalBlue, FestivalYellow, FestivalGreen), 8, 30f, FestivalMotion.FLOAT, 9_000)),
    ),
    PONGAL(
        "pongal",
        R.string.festival_pongal,
        listOf(FestivalElement(FestivalShape.FLOWER, listOf(FestivalYellow, FestivalOrange), 14, 18f, FestivalMotion.FALL, 9_000)),
    ),
    BAISAKHI(
        "baisakhi",
        R.string.festival_baisakhi,
        listOf(FestivalElement(FestivalShape.FLOWER, listOf(FestivalYellow, FestivalGreen), 16, 18f, FestivalMotion.FALL, 9_000)),
    ),
    ONAM(
        "onam",
        R.string.festival_onam,
        listOf(FestivalElement(FestivalShape.FLOWER, listOf(FestivalWhite, FestivalYellow, FestivalOrange), 14, 20f, FestivalMotion.FALL, 9_000)),
    ),
    DUSSEHRA(
        "dussehra",
        R.string.festival_dussehra,
        listOf(
            FestivalElement(FestivalShape.FLAME, listOf(FestivalOrange, FestivalRed), 7, 26f, FestivalMotion.RISE, 6_000),
            FestivalElement(FestivalShape.DOT, listOf(FestivalOrange, FestivalYellow), 12, 6f, FestivalMotion.RISE, 7_000),
        ),
    ),
    KARVA_CHAUTH(
        "karva_chauth",
        R.string.festival_karva_chauth,
        listOf(
            FestivalElement(FestivalShape.MOON, listOf(FestivalWhite), 4, 30f, FestivalMotion.FLOAT, 8_000),
            FestivalElement(FestivalShape.STAR, listOf(FestivalWhite, FestivalYellow), 16, 7f, FestivalMotion.FLOAT, 6_000),
        ),
    ),
    GURU_NANAK_JAYANTI(
        "guru_nanak_jayanti",
        R.string.festival_guru_nanak,
        listOf(
            FestivalElement(FestivalShape.DIYA, listOf(DiyaFlameOuter), 5, 30f, FestivalMotion.RISE, 11_000),
            FestivalElement(FestivalShape.DOT, listOf(FestivalYellow, FestivalWhite), 12, 7f, FestivalMotion.FLOAT, 6_000),
        ),
    ),
    MAHASHIVRATRI(
        "mahashivratri",
        R.string.festival_mahashivratri,
        listOf(FestivalElement(FestivalShape.LEAF, listOf(FestivalGreen, FestivalWhite), 18, 13f, FestivalMotion.FALL, 9_000)),
    ),
    INDEPENDENCE_DAY(
        "independence_day",
        R.string.festival_independence_day,
        listOf(FestivalElement(FestivalShape.BALLOON, listOf(FestivalOrange, FestivalWhite, FestivalGreen), 8, 28f, FestivalMotion.RISE, 10_000)),
    ),
    REPUBLIC_DAY(
        "republic_day",
        R.string.festival_republic_day,
        listOf(
            FestivalElement(FestivalShape.BALLOON, listOf(FestivalOrange, FestivalWhite, FestivalGreen), 5, 26f, FestivalMotion.RISE, 10_000),
            FestivalElement(FestivalShape.DOT, listOf(FestivalOrange, FestivalWhite, FestivalGreen), 20, 7f, FestivalMotion.FALL, 8_000),
        ),
    ),
    LOHRI(
        "lohri",
        R.string.festival_lohri,
        listOf(FestivalElement(FestivalShape.FLAME, listOf(FestivalOrange, FestivalRed, FestivalYellow), 8, 30f, FestivalMotion.RISE, 5_000)),
    ),
    RAM_NAVAMI(
        "ram_navami",
        R.string.festival_ram_navami,
        listOf(FestivalElement(FestivalShape.FLOWER, listOf(FestivalPink, FestivalWhite), 14, 18f, FestivalMotion.FALL, 9_000)),
    ),
    VALENTINES_DAY(
        "valentines_day",
        R.string.festival_valentines_day,
        listOf(FestivalElement(FestivalShape.HEART, listOf(FestivalRed, FestivalPink), 12, 20f, FestivalMotion.RISE, 8_000)),
    ),
    EASTER(
        "easter",
        R.string.festival_easter,
        listOf(
            FestivalElement(FestivalShape.EGG, listOf(FestivalPink, FestivalYellow, FestivalBlue), 8, 24f, FestivalMotion.FLOAT, 8_000),
            FestivalElement(FestivalShape.DOT, listOf(FestivalPink, FestivalYellow, FestivalTeal), 14, 7f, FestivalMotion.FLOAT, 6_000),
        ),
    ),
    THANKSGIVING(
        "thanksgiving",
        R.string.festival_thanksgiving,
        listOf(FestivalElement(FestivalShape.LEAF, listOf(FestivalOrange, FestivalBrown, FestivalYellow), 20, 13f, FestivalMotion.FALL, 9_000)),
    ),
    ST_PATRICKS_DAY(
        "st_patricks_day",
        R.string.festival_st_patricks_day,
        listOf(FestivalElement(FestivalShape.SHAMROCK, listOf(FestivalGreen, FestivalYellow), 12, 22f, FestivalMotion.FALL, 9_000)),
    ),
    CHINESE_NEW_YEAR(
        "chinese_new_year",
        R.string.festival_chinese_new_year,
        listOf(FestivalElement(FestivalShape.LANTERN, listOf(FestivalRed, FestivalYellow), 7, 28f, FestivalMotion.FLOAT, 9_000)),
    ),
    EARTH_DAY(
        "earth_day",
        R.string.festival_earth_day,
        listOf(
            FestivalElement(FestivalShape.LEAF, listOf(FestivalGreen, FestivalTeal), 16, 13f, FestivalMotion.FALL, 9_000),
            FestivalElement(FestivalShape.DOT, listOf(FestivalGreen, FestivalBlue), 12, 7f, FestivalMotion.FLOAT, 6_000),
        ),
    ),
    ;

    companion object {
        fun byId(id: String?): FestivalBackground =
            entries.firstOrNull { it.id == id } ?: NONE
    }
}
