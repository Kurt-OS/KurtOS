package kernel.graphics

import hal.UART
import kernel.drivers.virtio.VirtioMmioBus
import kernel.fdt.DeviceTree

object KernelGraphicsCommands {
    private var deviceTree: DeviceTree? = null

    fun initialize(deviceTree: DeviceTree?) {
        this.deviceTree = deviceTree
    }

    fun dispatch(input: String): Boolean {
        val parts = input.trim().split(" ").filter { it.isNotEmpty() }
        when (parts.firstOrNull() ?: "") {
            "gfx-info" -> {
                val ok = GraphicsService.initialize(deviceTree)
                UART.println("Graphics: ${GraphicsService.status()} (${if (ok) "online" else "offline"})")
            }
            "gfx-clear" -> {
                val color = parts.getOrNull(1)?.toUIntOrNull(16) ?: 0x004080c0u
                if (GraphicsService.initialize(deviceTree)) {
                    GraphicsService.framebuffer()?.clear(color)
                    GraphicsService.framebuffer()?.present()
                    UART.println("Graphics cleared.")
                } else {
                    UART.println("Graphics: ${GraphicsService.status()}")
                }
            }
            "gfx-test" -> runTestPattern()
            "virtio-scan" -> scanVirtio()
            else -> return false
        }
        return true
    }

    fun printHelp() {
        UART.println("  gfx-info   - show graphics device status")
        UART.println("  gfx-clear  - clear framebuffer; optional hex color")
        UART.println("  gfx-test   - draw a framebuffer test pattern")
        UART.println("  virtio-scan - list visible virtio-mmio devices")
    }

    private fun scanVirtio() {
        val devices = VirtioMmioBus.discover(deviceTree)
        if (devices.isEmpty()) {
            UART.println("Virtio: no MMIO devices visible")
            return
        }
        devices.forEach { device ->
            UART.println("Virtio: id=${device.deviceId} version=${device.version} base=0x${device.base.toString(16)}")
        }
    }

    private fun runTestPattern() {
        if (!GraphicsService.initialize(deviceTree)) {
            UART.println("Graphics: ${GraphicsService.status()}")
            return
        }

        val fb = GraphicsService.framebuffer()
        if (fb == null) {
            UART.println("Graphics: framebuffer unavailable")
            return
        }

        fb.clear(0x00101820u)
        fb.fillRect(24u, 24u, fb.mode.width / 3u, fb.mode.height / 3u, 0x00d94848u)
        fb.fillRect(fb.mode.width / 3u, fb.mode.height / 4u, fb.mode.width / 3u, fb.mode.height / 3u, 0x0048d96fu)
        fb.fillRect(fb.mode.width / 2u, fb.mode.height / 2u, fb.mode.width / 3u, fb.mode.height / 3u, 0x004875d9u)
        fb.present()
        UART.println("Graphics test pattern presented.")
    }
}
