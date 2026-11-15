package io.github.aedev.flow.utils

/**
 * Splits session plays the server has not recorded into the ones new enough to
 * head a server-backed history list and the ones that belong after it.
 *
 * WHY: the server list is the account's newest-first order but carries no watch
 * times, so a local play can only jump ahead of it when its own timestamp
 * proves it is newer than the newest local play the server HAS recorded. The
 * anchor is the server list's first entry when this device has it, else the
 * newest timestamp among local plays anywhere in the server list. Without this
 * bound every unrecorded row heads the list forever — including music, which
 * the server never records at all, so a song from yesterday sat above a video
 * watched minutes ago (2026-11-14). With no overlap at all and a non-empty
 * server list, nothing heads: recency that cannot be proven must not jump the
 * queue. An empty server list is a pure local list, so everything heads.
 */
fun partitionUnrecordedLocal(
    local: List<Pair<String, Long>>,
    serverIdsInOrder: List<String>,
): Pair<Set<String>, Set<String>> {
    val localById = local.toMap()
    val serverIds = serverIdsInOrder.toSet()
    val anchor =
        serverIdsInOrder.firstOrNull()?.let { localById[it] }
            ?: local.filter { (id, _) -> id in serverIds }.maxOfOrNull { (_, timestamp) -> timestamp }
    val unrecorded = local.filter { (id, _) -> id !in serverIds }
    val headIds =
        when {
            anchor != null -> unrecorded.filter { (_, timestamp) -> timestamp > anchor }.map { (id, _) -> id }.toSet()
            serverIds.isEmpty() -> unrecorded.map { (id, _) -> id }.toSet()
            else -> emptySet()
        }
    return headIds to (unrecorded.map { (id, _) -> id }.toSet() - headIds)
}
