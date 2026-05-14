package kernel.graphics

import graphics.GraphicsConstants
import kernel.drivers.virtio.VirtioMmioTransport
import kernel.memory.DmaBuffer
import kernel.memory.PageAllocator

internal class VirtioGpuDevice(private val transport: VirtioMmioTransport) : DisplayDevice {
    override val name: String = transport.name
    override var status: String = "not initialized"
        private set

    private lateinit var queue: kernel.drivers.virtio.VirtQueue
    private lateinit var framebufferMemory: DmaBuffer
    private var framebuffer: Framebuffer? = null
    private var mode: GraphicsMode = GraphicsMode(640u, 480u, 640u * 4u)
    private val resourceId: UInt = 1u

    fun initialize(): Framebuffer? {
        framebuffer?.let { return it }
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
        this.framebuffer = framebuffer
        status = "ready"
        return framebuffer
    }

    override fun framebuffer(): Framebuffer? = framebuffer

    fun present(x: UInt, y: UInt, width: UInt, height: UInt): Boolean {
        if (width == 0u || height == 0u) return true
        return transferToHost2D(x, y, width, height) && flushResource(x, y, width, height)
    }

    private fun queryDisplayMode(): GraphicsMode {
        val response = command(GraphicsConstants.CMD_GET_DISPLAY_INFO, 24u, 512u)
        if (response == null || response.read32(0u) != GraphicsConstants.RESP_OK_DISPLAY_INFO) {
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
        val request = commandBuffer(GraphicsConstants.CMD_RESOURCE_CREATE_2D, 40u)
        request.write32(24u, resourceId)
        request.write32(28u, GraphicsConstants.VIRTIO_GPU_FORMAT_B8G8R8X8_UNORM)
        request.write32(32u, mode.width)
        request.write32(36u, mode.height)
        return submitExpectNoData(request, 40u)
    }

    private fun attachBacking(): Boolean {
        val request = commandBuffer(GraphicsConstants.CMD_RESOURCE_ATTACH_BACKING, 48u)
        request.write32(24u, resourceId)
        request.write32(28u, 1u)
        request.write64(32u, framebufferMemory.physicalAddress)
        request.write32(40u, mode.strideBytes * mode.height)
        request.write32(44u, 0u)
        return submitExpectNoData(request, 48u)
    }

    private fun setScanout(): Boolean {
        val request = commandBuffer(GraphicsConstants.CMD_SET_SCANOUT, 48u)
        writeRect(request, 24u, 0u, 0u, mode.width, mode.height)
        request.write32(40u, 0u)
        request.write32(44u, resourceId)
        return submitExpectNoData(request, 48u)
    }

    private fun transferToHost2D(x: UInt, y: UInt, width: UInt, height: UInt): Boolean {
        val request = commandBuffer(GraphicsConstants.CMD_TRANSFER_TO_HOST_2D, 56u)
        writeRect(request, 24u, x, y, width, height)
        request.write64(40u, 0UL)
        request.write32(48u, resourceId)
        request.write32(52u, 0u)
        return submitExpectNoData(request, 56u)
    }

    private fun flushResource(x: UInt, y: UInt, width: UInt, height: UInt): Boolean {
        val request = commandBuffer(GraphicsConstants.CMD_RESOURCE_FLUSH, 48u)
        writeRect(request, 24u, x, y, width, height)
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
        if (type != GraphicsConstants.RESP_OK_NODATA) {
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

    private fun writeRect(buffer: DmaBuffer, offset: UInt, x: UInt, y: UInt, width: UInt, height: UInt) {
        buffer.write32(offset, x)
        buffer.write32(offset + 4u, y)
        buffer.write32(offset + 8u, width)
        buffer.write32(offset + 12u, height)
    }
}
