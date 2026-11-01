package io.github.aedev.flow.ui.screens.home.chips

/** The interest clusters Home last showed as chips, kept so the row does not reshuffle every load. */
data class InterestChipSet(
    /** Cluster representative to its mass when chosen, in chip order. */
    val masses: Map<String, Double> = emptyMap(),
    val computedAt: Long = 0L,
)

/**
 * One interest chip: the cluster it opens and the topics that define it. A theme chip has a YouTube
 * [category], opens several [clusters] and searches each of its [parts] in turn.
 */
data class InterestChip(
    val representative: String,
    val label: String,
    val topics: List<String>,
    val category: String? = null,
    val parts: List<InterestChip> = emptyList(),
    val clusters: List<String> = listOf(representative),
)
