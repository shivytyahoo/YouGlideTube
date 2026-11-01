package io.github.aedev.flow.data.engagement

/** How the viewer treated a video, as classified by the watch/shorts classifiers. */
enum class InteractionType {
    CLICK,
    LIKED,
    WATCHED,
    SKIPPED,
    DISLIKED,

    /** High-intent save: download, Watch Later, playlist add. Positive but weaker than LIKED. */
    SAVED,
}
