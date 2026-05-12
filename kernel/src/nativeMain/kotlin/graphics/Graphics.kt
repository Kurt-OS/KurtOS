package kernel.graphics

import hal.RawMemory
import kernel.drivers.virtio.VirtioMmioBus
import kernel.drivers.virtio.VirtioMmioTransport
import kernel.fdt.DeviceTree
import kernel.memory.DmaBuffer
import kernel.memory.PageAllocator

data class GraphicsMode(val width: UInt, val height: UInt, val strideBytes: UInt)

class Framebuffer internal constructor(
    private val device: VirtioGpuDevice,
    val mode: GraphicsMode,
    private val buffer: DmaBuffer,
) {
    fun clear(color: UInt) {
        fillRect(0u, 0u, mode.width, mode.height, color)
    }

    fun setPixel(x: UInt, y: UInt, color: UInt) {
        if (x >= mode.width || y >= mode.height) return
        RawMemory.write32(buffer.physicalAddress + y.toULong() * mode.strideBytes.toULong() + x.toULong() * 4UL, color)
    }

    fun fillRect(x: UInt, y: UInt, width: UInt, height: UInt, color: UInt) {
        val endX = minOf(mode.width, x + width)
        val endY = minOf(mode.height, y + height)
        var py = y
        while (py < endY) {
            var px = x
            while (px < endX) {
                setPixel(px, py, color)
                px++
            }
            py++
        }
    }

    fun present(): Boolean = device.present()
}

object GraphicsService {
    private var device: VirtioGpuDevice? = null
    private var framebuffer: Framebuffer? = null
    private var lastStatus: String = "not initialized"

    fun initialize(deviceTree: DeviceTree?): Boolean {
        if (framebuffer != null) return true

        val gpuTransport = VirtioMmioBus.discover(deviceTree).firstOrNull { it.deviceId == VIRTIO_GPU_DEVICE_ID }
        if (gpuTransport == null) {
            lastStatus = "virtio-gpu not found"
            return false
        }

        val gpu = VirtioGpuDevice(gpuTransport)
        val fb = gpu.initialize()
        if (fb == null) {
            lastStatus = gpu.status
            return false
        }

        device = gpu
        framebuffer = fb
        lastStatus = "ready ${fb.mode.width}x${fb.mode.height}"
        return true
    }

    fun framebuffer(): Framebuffer? = framebuffer

    fun status(): String = lastStatus
}

private const val VIRTIO_GPU_DEVICE_ID: UInt = 16u
private const val VIRTIO_GPU_FORMAT_B8G8R8X8_UNORM: UInt = 2u

private const val CMD_GET_DISPLAY_INFO: UInt = 0x0100u
private const val CMD_RESOURCE_CREATE_2D: UInt = 0x0101u
private const val CMD_SET_SCANOUT: UInt = 0x0103u
private const val CMD_RESOURCE_FLUSH: UInt = 0x0104u
private const val CMD_TRANSFER_TO_HOST_2D: UInt = 0x0105u
private const val CMD_RESOURCE_ATTACH_BACKING: UInt = 0x0106u

private const val RESP_OK_NODATA: UInt = 0x1100u
private const val RESP_OK_DISPLAY_INFO: UInt = 0x1101u

internal class VirtioGpuDevice(private val transport: VirtioMmioTransport) {
    var status: String = "not initialized"
        private set

    private lateinit var queue: kernel.drivers.virtio.VirtQueue
    private lateinit var framebufferMemory: DmaBuffer
    private var mode: GraphicsMode = GraphicsMode(640u, 480u, 640u * 4u)
    private val resourceId: UInt = 1u

    fun initialize(): Framebuffer? {
        if (!transport.initializeNoFeatures()) {
            status = "feature negotiation failed"
            return null
        }

        queue = transport.createQueue(0u, 8u)
        transport.driverOk()

        mode = queryDisplayMode()
        framebufferMemory = PageAllocator.allocateBytes(mode.strideBytes * mode.height)

        if (!createResource2D() || !attachBacking() || !setScanout()) {
            return null
        }

        val framebuffer = Framebuffer(this, mode, framebufferMemory)
        framebuffer.clear(0x00102030u)
        framebuffer.present()
        status = "ready"
        return framebuffer
    }

    fun present(): Boolean {
        return transferToHost2D() && flushResource()
    }

    private fun queryDisplayMode(): GraphicsMode {
        val response = command(CMD_GET_DISPLAY_INFO, 24u, 512u)
        if (response == null || response.read32(0u) != RESP_OK_DISPLAY_INFO) {
            status = "display info unavailable"
            return GraphicsMode(640u, 480u, 640u * 4u)
        }

        val modeOffset = 24u
        val width = response.read32(modeOffset + 8u)
        val height = response.read32(modeOffset + 12u)
        val enabled = response.read32(modeOffset + 16u)
        if (enabled == 0u || width == 0u || height == 0u) {
            return GraphicsMode(640u, 480u, 640u * 4u)
        }
        return GraphicsMode(width, height, width * 4u)
    }

    private fun createResource2D(): Boolean {
        val request = commandBuffer(CMD_RESOURCE_CREATE_2D, 40u)
        request.write32(24u, resourceId)
        request.write32(28u, VIRTIO_GPU_FORMAT_B8G8R8X8_UNORM)
        request.write32(32u, mode.width)
        request.write32(36u, mode.height)
        return submitExpectNoData(request, 40u)
    }

    private fun attachBacking(): Boolean {
        val request = commandBuffer(CMD_RESOURCE_ATTACH_BACKING, 48u)
        request.write32(24u, resourceId)
        request.write32(28u, 1u)
        request.write64(32u, framebufferMemory.physicalAddress)
        request.write32(40u, mode.strideBytes * mode.height)
        request.write32(44u, 0u)
        return submitExpectNoData(request, 48u)
    }

    private fun setScanout(): Boolean {
        val request = commandBuffer(CMD_SET_SCANOUT, 48u)
        writeRect(request, 24u)
        request.write32(40u, 0u)
        request.write32(44u, resourceId)
        return submitExpectNoData(request, 48u)
    }

    private fun transferToHost2D(): Boolean {
        val request = commandBuffer(CMD_TRANSFER_TO_HOST_2D, 56u)
        writeRect(request, 24u)
        request.write64(40u, 0UL)
        request.write32(48u, resourceId)
        request.write32(52u, 0u)
        return submitExpectNoData(request, 56u)
    }

    private fun flushResource(): Boolean {
        val request = commandBuffer(CMD_RESOURCE_FLUSH, 48u)
        writeRect(request, 24u)
        request.write32(40u, resourceId)
        request.write32(44u, 0u)
        return submitExpectNoData(request, 48u)
    }

    private fun submitExpectNoData(request: DmaBuffer, requestLength: UInt): Boolean {
        val response = PageAllocator.allocateBytes(64u)
        val ok = queue.submit(request, requestLength, response, 64u)
        if (!ok) {
            status = "virtqueue timeout"
            return false
        }
        val type = response.read32(0u)
        if (type != RESP_OK_NODATA) {
            status = "gpu command failed: 0x${type.toString(16)}"
            return false
        }
        return true
    }

    private fun command(type: UInt, requestLength: UInt, responseLength: UInt): DmaBuffer? {
        val request = commandBuffer(type, requestLength)
        val response = PageAllocator.allocateBytes(responseLength)
        if (!queue.submit(request, requestLength, response, responseLength)) {
            status = "virtqueue timeout"
            return null
        }
        return response
    }

    private fun commandBuffer(type: UInt, size: UInt): DmaBuffer {
        val request = PageAllocator.allocateBytes(size)
        request.write32(0u, type)
        request.write32(4u, 0u)
        request.write64(8u, 0UL)
        request.write32(16u, 0u)
        request.write32(20u, 0u)
        return request
    }

    private fun writeRect(buffer: DmaBuffer, offset: UInt) {
        buffer.write32(offset, 0u)
        buffer.write32(offset + 4u, 0u)
        buffer.write32(offset + 8u, mode.width)
        buffer.write32(offset + 12u, mode.height)
    }
}
