package io.github.shivytyahoo.glidetube.ui.screens.home.chips

/**
 * YouTube's own topic pages: each is a channel of YouTube's whose first tab is one flat list
 * of videos and playlists, so a plain channel browse loads it — no sign-in, no special
 * endpoint. What they hold depends on the region, so an empty page says so.
 */
internal val topicChannels =
    linkedMapOf(
        "Music" to "UC-9-kyTW8ZkZNDHQJ6FgpwQ",
        "Live" to "UC4R8DWoMoI7CAwX8_LjQHig",
        "News" to "UCYfdidRxbB8Qhf0Nx7ioOYw",
        "Sports" to "UCEgdi0XIXXZ-qJOFPf4JSKw",
        "Learning" to "UCtFRv9O2AHqOZjjynzrv-xg",
        "Gaming" to "UCOpNcN46UbXVtpKMrmU4Abg",
        "Fashion & beauty" to "UCrpQ4p1Ql_hG8rKXIKM1MOQ",
    )

/** The topics that are chips in the row; the rest are under More. */
internal val topTopicChannels = listOf("Live")
