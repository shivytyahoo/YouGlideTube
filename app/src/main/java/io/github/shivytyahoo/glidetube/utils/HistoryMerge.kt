package io.github.shivytyahoo.glidetube.utils

/**
 * Merges a server history list with this device's plays into one newest-first
 * order, keyed by the best-known watch time for each video.
 *
 * WHY: the server list carries no watch times, and Report 48 proved its order
 * cannot be trusted for entries this device knows — YouTube listed Surprise
 * (06:15) and Tera Mera Rishta (06:05) above Rohit/Kohli (08:25), which this
 * device watched two hours later and never re-played. The server is only a
 * relative order; a local timestamp is the ground truth for its own video, so
 * every entry sorts by its local timestamp where one exists. A server-only
 * entry keeps its server-relative position by interpolation between the
 * nearest local timestamps that bracket it (or a 1 s step past the nearest
 * one at either end), so yesterday's server rows still fall below today's
 * plays instead of heading the list. With no overlap at all the server list
 * cannot be anchored, so it keeps its order first and local rows follow, as
 * before. An empty server list is a pure local list.
 */
fun mergeHistoryOrder(
    local: List<Pair<String, Long>>,
    serverIdsInOrder: List<String>,
    now: Long,
): List<Pair<String, Long>> {
    val localById = local.toMap()
    val serverIndex = serverIdsInOrder.withIndex().associate { it.value to it.index }
    val localIndex = local.withIndex().associate { it.value.first to it.index }
    if (serverIdsInOrder.isEmpty()) {
        return local.sortedByDescending { (_, timestamp) -> timestamp }
    }
    val knownServer = serverIdsInOrder.withIndex().filter { (_, id) -> id in localById }
    if (knownServer.isEmpty()) {
        val serverPart = serverIdsInOrder.mapIndexed { index, id -> id to now - index * 1_000L }
        val localPart =
            local.filter { (id, _) -> id !in serverIndex }
                .sortedByDescending { (_, timestamp) -> timestamp }
                .mapIndexed { index, (id, _) -> id to now - (serverIdsInOrder.size + index) * 1_000L }
        return serverPart + localPart
    }
    val effective = HashMap<String, Long>(serverIdsInOrder.size + localById.size)
    for ((index, id) in serverIdsInOrder.withIndex()) {
        val known = localById[id]
        if (known != null) {
            effective[id] = known
            continue
        }
        val prevKnown = knownServer.lastOrNull { it.index < index }
        val nextKnown = knownServer.firstOrNull { it.index > index }
        val interpolated =
            if (prevKnown != null) {
                val prevTs = localById.getValue(prevKnown.value)
                if (nextKnown != null) {
                    val nextTs = localById.getValue(nextKnown.value)
                    prevTs + (nextTs - prevTs) * (index - prevKnown.index) / (nextKnown.index - prevKnown.index)
                } else {
                    prevTs - (index - prevKnown.index) * 1_000L
                }
            } else {
                // No known entry precedes this one: the first known entry
                // after it is the only bracket, so this row sits above it
                // by its distance. nextKnown is null only when no known
                // entry follows either, which cannot happen here because
                // knownServer is non-empty, so first() is the same row.
                val next = nextKnown ?: knownServer.first()
                localById.getValue(next.value) + (next.index - index) * 1_000L
            }
        effective[id] = minOf(now, interpolated)
    }
    for ((id, timestamp) in localById) {
        if (id !in effective) effective[id] = timestamp
    }
    return effective.entries
        .sortedWith(
            compareByDescending<Map.Entry<String, Long>> { it.value }
                .thenBy { serverIndex[it.key] ?: Int.MAX_VALUE }
                .thenBy { localIndex[it.key] ?: Int.MAX_VALUE },
        )
        .map { it.key to it.value }
}
