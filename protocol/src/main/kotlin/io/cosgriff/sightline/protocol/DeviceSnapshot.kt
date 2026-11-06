package io.cosgriff.sightline.protocol

/** One getset and what the device said to it. */
data class Probe(val name: String, val result: ControlResult)

/** Everything one tuner says about itself, read-only. */
data class TunerSnapshot(
    val tuner: Int,
    val probes: List<Probe>,
) {
    fun value(item: String): String? = (probes.firstOrNull { it.name.endsWith("/$item") }?.result as? ControlResult.Value)?.value

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
) {
    /** True when nothing answered: the device is off the network, or the address is wrong. */
    val unreachable: Boolean
        get() = (system + tuners.flatMap { it.probes }).all { it.result is ControlResult.Unreachable }

    companion object {
        /** The `/sys/` values read, in display order. `/sys/model` is the firmware name, `/sys/hwmodel` the model. */
        val SYSTEM = listOf("/sys/hwmodel", "/sys/model", "/sys/version", "/sys/features")

        /** Each tuner's values read. `vstatus` doesn't exist on the FLEX 4K; asking would only show an error. */
        val TUNER = listOf("status", "debug", "lockkey", "target", "channel", "vchannel", "streaminfo", "plpinfo")

        /**
         * Reads [SYSTEM] and, for each of [tunerCount] tuners, [TUNER]. Gets only: never a set, so it works on
         * tuners a DVR is using and can't disturb them. Stops early when the device doesn't answer at all.
         */
        suspend fun read(control: ControlClient, tunerCount: Int): DeviceSnapshot {
            val system = SYSTEM.map { Probe(it, control.get(it)) }
            val dead = system.all { it.result is ControlResult.Unreachable }
            val tuners = (0 until tunerCount).map { n ->
                TunerSnapshot(
                    n,
                    if (dead) emptyList()
                    else TUNER.map { item -> "/tuner$n/$item".let { Probe(it, control.get(it)) } },
                )
            }
            return DeviceSnapshot(system, tuners, control.localAddress?.hostAddress)
        }
    }
}
