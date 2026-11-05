package io.cosgriff.sightline.data.device

/**
 * The Demo tuner, provided by debug builds only (`FakeHdhr`, in `:app`'s `src/debug`). Release builds have no
 * binding, so nothing of it exists there. Every screen works against it, so nothing needs hardware to build or
 * test.
 */
interface DemoDevice {
    val device: Device
}
