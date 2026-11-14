package io.cosgriff.sightline.protocol

import kotlin.time.TimeSource

/** One getset and what the device said to it. */
data class Probe(val name: String, val result: ControlResult)

/** Everything one tuner says about itself, read-only. */
data class TunerSnapshot(
    val tuner: Int,
    val probes: List<Probe>,
) {
    fun value(item: String): String? = (probes.firstOrNull { it.name.endsWith("/$item") }?.result as? ControlResult.Value)?.value

    /** True when the device refused the status get with `tuner busy`, as it does during its own channel scan. */
    val deviceBusy: Boolean
        get() = ((probes.firstOrNull { it.name.endsWith("/status") }?.result as? ControlResult.Rejected)?.error is ControlError.TunerBusy)

    val status: TunerStatus? get() = value("status")?.let { (StatusParser.parse(it) as? StatusParse.Parsed)?.status }
    val debug: TunerDebug? get() = value("debug")?.let { TunerDebug.parse(it) }

    /** The lock holder's IP, or null when there is none (`none`) or it didn't answer. */
    val lockHolder: String? get() = value("lockkey")?.takeIf { it != "none" }

    /** Where the tuner is streaming (`http://<ip>:<port>`, `udp://…`), null for `none`. */
    val target: String? get() = value("target")?.takeIf { it != "none" }
}

/** A read-only look at a device: its `/sys/` values and every tuner's. */
data class DeviceSnapshot(
    val system: List<Probe>,
    val tuners: List<TunerSnapshot>,
    /** This phone's address on the control connection, to tell its own locks from other clients'. */
    val localAddress: String?,
    /** How long the device took to answer the first get, in ms; null when it didn't. Slow over Wi-Fi shows here. */
    val replyMs: Long? = null,
) {
    /** `/sys/debug`, parsed; null when it didn't answer. */
    val sysDebug: SysDebug?
        get() = (system.firstOrNull { it.name == "/sys/debug" }?.result as? ControlResult.Value)?.value?.let(SysDebug::parse)

    /** True when nothing answered: the device is off the network, or the address is wrong. */
    val unreachable: Boolean
        get() = (system + tuners.flatMap { it.probes }).all { it.result is ControlResult.Unreachable }

    companion object {
        /** The `/sys/` values read, in display order. `/sys/model` is the firmware name, `/sys/hwmodel` the model. */
        val SYSTEM = listOf("/sys/hwmodel", "/sys/model", "/sys/version", "/sys/features", "/sys/debug")

        /** Each tuner's values read. `vstatus` doesn't exist on the FLEX 4K; asking would only show an error. */
        val TUNER = listOf(
            "status", "debug", "lockkey", "target", "channel", "vchannel", "program", "channelmap", "streaminfo", "plpinfo",
        )

        /**
         * Reads [SYSTEM] and, for each of [tunerCount] tuners, [TUNER]. Gets only: never a set, so it works on
         * tuners a DVR is using and can't disturb them. Stops early when the device doesn't answer at all.
         */
        suspend fun read(control: ControlClient, tunerCount: Int): DeviceSnapshot {
            // The first get is timed: it may also open the connection, which is part of what a slow network costs.
            val mark = TimeSource.Monotonic.markNow()
            val first = Probe(SYSTEM.first(), control.get(SYSTEM.first()))
            val replyMs = mark.elapsedNow().inWholeMilliseconds.takeIf { first.result !is ControlResult.Unreachable }
            val system = listOf(first) + SYSTEM.drop(1).map { Probe(it, control.get(it)) }
            val dead = system.all { it.result is ControlResult.Unreachable }
            val tuners = (0 until tunerCount).map { n ->
                TunerSnapshot(
                    n,
                    if (dead) emptyList()
                    else TUNER.map { item -> "/tuner$n/$item".let { Probe(it, control.get(it)) } },
                )
            }
            return DeviceSnapshot(system, tuners, control.localAddress?.hostAddress, replyMs)
        }
    }
}
