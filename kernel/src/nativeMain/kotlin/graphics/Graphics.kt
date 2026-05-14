package graphics

import drivers.DeviceCapability
import drivers.DeviceManager
import fdt.DeviceTree

data class GraphicsMode(val width: UInt, val height: UInt, val strideBytes: UInt)

interface DisplayDevice : DeviceCapability {
    val status: String
    fun framebuffer(): Framebuffer?
}

object GraphicsService {
    private var framebuffer: Framebuffer? = null
    private var lastStatus: String = "not initialized"

    fun initialize(deviceTree: DeviceTree?): Boolean {
        if (framebuffer != null) return true

        DeviceManager.initialize(deviceTree)
        val display = DeviceManager.displayDevices().firstOrNull()
        if (display == null) {
            lastStatus = "no display devices"
            return false
        }

        val fb = display.framebuffer()
        if (fb == null) {
            lastStatus = display.status
            return false
        }

        framebuffer = fb
        lastStatus = "ready ${fb.mode.width}x${fb.mode.height} ${display.name}"
        return true
    }

    fun framebuffer(): Framebuffer? = framebuffer

    fun status(): String = lastStatus
}