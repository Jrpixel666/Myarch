package dev.myarch.runtime.mirror

/** The outcome of checking whether a mirror is usable. */
public sealed interface ProbeResult {
    /** The mirror answered; [latencyMillis] is how long it took. */
    public data class Reachable(val latencyMillis: Long, val sizeBytes: Long?) : ProbeResult

    /** The mirror could not be used, with a human-readable reason. */
    public data class Unreachable(val reason: String) : ProbeResult
}

/**
 * Checks whether a URL can be downloaded from.
 *
 * Synchronous on purpose: the installer already runs on a background thread,
 * and a suspending probe would force every caller to be a coroutine for no
 * benefit.
 */
public fun interface MirrorProbe {
    public fun probe(url: String): ProbeResult
}

/**
 * Chooses which mirror to download from, and switches when one fails.
 *
 * This is the piece behind the installer's mirror switcher. It has two jobs:
 *
 * 1. **Ordering.** Verified mirrors first, then anything the user has had
 *    success with before, then the rest. If probe results are available they
 *    take over: a mirror that answers in 200 ms beats one that takes 3 s.
 * 2. **Fallback.** When a download fails, [onFailure] retires that mirror for
 *    this run and [current] moves to the next candidate, so the installer can
 *    simply retry in a loop instead of asking the user to intervene.
 *
 * A mirror the user picked explicitly is pinned: it is tried first and is not
 * retired, because overriding an explicit choice silently is how people end up
 * downloading 700 MB from the other side of the planet.
 */
public class MirrorRotation(
    candidates: List<Mirror>,
    private val pinned: Mirror? = null,
) {
    private val remaining: MutableList<Mirror> = buildList {
        if (pinned != null) add(pinned)
        addAll(candidates.filter { it.id != pinned?.id })
    }.toMutableList()

    private val failures = LinkedHashMap<String, String>()
    private val latencies = HashMap<String, Long>()

    /** The mirror to use right now, or `null` once every option is spent. */
    public val current: Mirror? get() = remaining.firstOrNull()

    /** Mirrors not yet tried, in the order they will be tried. */
    public fun pending(): List<Mirror> = remaining.toList()

    /** Why each retired mirror was dropped, for the installer's log. */
    public fun failureReasons(): Map<String, String> = failures.toMap()

    /**
     * Retires [mirror] for this run and returns the next one to try.
     *
     * The pinned mirror is kept: an explicit choice is not overridden, so the
     * caller sees `current` unchanged and can surface the error instead.
     */
    public fun onFailure(mirror: Mirror, reason: String): Mirror? {
        failures[mirror.id] = reason
        if (mirror.id != pinned?.id) {
            remaining.removeAll { it.id == mirror.id }
        } else if (remaining.size > 1) {
            // Move the pin to the back rather than dropping it, so a transient
            // failure does not lose the user's preference entirely.
            remaining.removeAt(0)
            remaining.add(mirror)
        }
        return current
    }

    /**
     * Re-orders the untried mirrors using probe results.
     *
     * Unreachable mirrors are retired. Reachable ones sort by latency, so the
     * installer downloads from the fastest responder rather than the first
     * entry in a hard-coded list.
     */
    public fun applyProbeResults(results: Map<String, ProbeResult>) {
        for ((id, result) in results) {
            when (result) {
                is ProbeResult.Reachable -> latencies[id] = result.latencyMillis
                is ProbeResult.Unreachable -> {
                    failures[id] = result.reason
                    if (id != pinned?.id) remaining.removeAll { it.id == id }
                }
            }
        }
        val probedPinned = pinned?.takeIf { remaining.any { m -> m.id == it.id } }
        val rest = remaining.filter { it.id != probedPinned?.id }
            .sortedWith(
                compareBy(
                    { latencies[it.id] ?: UNPROBED_LATENCY },
                    { if (it.verified) 0 else 1 },
                    { it.label },
                ),
            )
        remaining.clear()
        if (probedPinned != null) remaining.add(probedPinned)
        remaining.addAll(rest)
    }

    /** Probes every pending mirror's rootfs URL and re-orders accordingly. */
    public fun probeAll(probe: MirrorProbe, urlFor: (Mirror) -> String) {
        val results = pending().associate { mirror -> mirror.id to probe.probe(urlFor(mirror)) }
        applyProbeResults(results)
    }

    private companion object {
        /** Sorts unprobed mirrors after everything that answered. */
        const val UNPROBED_LATENCY = Long.MAX_VALUE / 2
    }
}
