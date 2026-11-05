package io.cosgriff.sightline.data.live

import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.device.TunerUse

/**
 * Which tuners the Multi-tuner grid starts on opening, and on which channels (SPEC Screens › 5). Every idle tuner
 * but one: the last idle tuner is left for a DVR, so a recording that starts while the grid is open still finds a
 * free tuner. The user can start that one from its cell. Tuners another client holds are never in the plan; ones
 * this app holds count as idle (Live lets its tuner go as the grid opens).
 *
 * The first started tuner gets [first] (Live's channel); each next one the next channel in the lineup on an RF
 * channel not already shown (subchannels share a signal), so the grid compares towers. Channels known to be
 * ATSC 3.0 are skipped after the first, since not every tuner can take them.
 */
object MultiTunerPlan {
    fun initial(uses: List<TunerUse>, channels: List<Channel>, first: Channel): Map<Int, Channel> {
        val idle = uses.indices.filter { uses[it] == TunerUse.Idle || uses[it] == TunerUse.Sightline }
        val start = idle.dropLast(1)
        if (start.isEmpty()) return emptyMap()
        val picked = mutableListOf(first)
        val from = channels.indexOfFirst { it.guideNumber == first.guideNumber }.coerceAtLeast(0)
        val rotated = channels.drop(from + 1) + channels.take(from)
        for (c in rotated) {
            if (picked.size == start.size) break
            if (c.atsc3) continue
            if (picked.any { it.guideNumber == c.guideNumber }) continue
            if (c.rfChannel != null && picked.any { it.rfChannel == c.rfChannel }) continue
            picked += c
        }
        // A short lineup: repeat channels rather than leave a tuner idle that was meant to start.
        val distinct = picked.toList()
        var i = 0
        while (picked.size < start.size) picked += distinct[i++ % distinct.size]
        return start.zip(picked).toMap()
    }
}
