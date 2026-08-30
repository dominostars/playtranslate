package com.playtranslate.translation

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Result of [dispatchPartitioned]: [all] is positionally aligned with the
 * input texts; [fromOnline] is only what the online waterfall produced (for
 * degraded-state aggregation, which must ignore deliberately-offline results
 * so an all-short page neither clears nor sets the online tier's badge).
 */
internal data class PartitionedResult<T>(
    val all: List<T>,
    val fromOnline: List<T>,
)

/**
 * Splits a batch between a per-text offline lambda and a single online batch
 * call, preserving positional alignment.
 *
 * The offline leg runs FIRST and IN PARALLEL (async/awaitAll — the same shape
 * the registry uses for non-batching backends), fully resolving before the
 * single [online] call — so an offline failure (null) folds into the SAME
 * online call as the long texts, keeping the hard "at most one online request
 * per pass" guarantee. Parallelism bounds the wait on a first-sight
 * menu-heavy page: Bergamot serializes inference on its own mutex, but the
 * per-text pre-work overlaps, and ML Kit's per-text GMS round-trips genuinely
 * parallelize. Callers should hand in an [offline] lambda that dispatches
 * off-main — this function inherits the caller's context.
 *
 * [online] is never invoked when nothing needs it — an all-short page makes no
 * online call at all (and therefore can no longer throw "all backends failed";
 * that is the feature). When invoked, it must return exactly one result per
 * input, in order (the registry's batch contract, guarded by the check).
 */
internal suspend fun <T : Any> dispatchPartitioned(
    texts: List<String>,
    routeOffline: List<Boolean>,
    offline: suspend (String) -> T?,
    online: suspend (List<String>) -> List<T>,
): PartitionedResult<T> {
    require(routeOffline.size == texts.size) {
        "routeOffline size ${routeOffline.size} != texts size ${texts.size}"
    }
    val out = arrayOfNulls<Any?>(texts.size)
    coroutineScope {
        texts.indices
            .filter { routeOffline[it] }
            .map { i -> async { out[i] = offline(texts[i]) } }
            .awaitAll()
    }
    val onlineIndices = texts.indices.filter { out[it] == null }
    val onlineOut = if (onlineIndices.isEmpty()) {
        emptyList()
    } else {
        online(onlineIndices.map { texts[it] })
    }
    check(onlineOut.size == onlineIndices.size) {
        "online batch returned ${onlineOut.size} results for ${onlineIndices.size} texts"
    }
    onlineIndices.forEachIndexed { k, orig -> out[orig] = onlineOut[k] }
    @Suppress("UNCHECKED_CAST")
    return PartitionedResult(out.toList() as List<T>, onlineOut)
}
