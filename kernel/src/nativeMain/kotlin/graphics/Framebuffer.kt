package graphics

import hal.RawMemory
import memory.DmaBuffer

class Framebuffer internal constructor(
    private val device: VirtioGpuDevice,
    val mode: GraphicsMode,
    private val buffer: DmaBuffer,
) {
    private var dirtyMinX: UInt = 0u
    private var dirtyMinY: UInt = 0u
    private var dirtyMaxX: UInt = 0u
    private var dirtyMaxY: UInt = 0u

    fun clear(color: UInt) {
        fillRect(0u, 0u, mode.width, mode.height, color)
    }

    fun setPixel(x: UInt, y: UInt, color: UInt) {
        if (x >= mode.width || y >= mode.height) return
        RawMemory.write32(buffer.physicalAddress + y.toULong() * mode.strideBytes.toULong() + x.toULong() * 4UL, color)
        markDirty(x, y, 1u, 1u)
    }

    fun fillRect(x: UInt, y: UInt, width: UInt, height: UInt, color: UInt) {
        if (x >= mode.width || y >= mode.height || width == 0u || height == 0u) return

        val endX = clippedEnd(x, width, mode.width)
        val endY = clippedEnd(y, height, mode.height)
        val rowPixels = endX - x
        var py = y
        while (py < endY) {
            val address = buffer.physicalAddress + py.toULong() * mode.strideBytes.toULong() + x.toULong() * 4UL
            RawMemory.fill32(address, color, rowPixels)
            py++
        }
        markDirty(x, y, rowPixels, endY - y)
    }

    fun drawIndexedImage(
        sourceWidth: UInt,
        sourceHeight: UInt,
        pixels: ByteArray,
        palette: UIntArray,
        x: UInt,
        y: UInt,
        width: UInt,
        height: UInt,
    ) {
        if (sourceWidth == 0u || sourceHeight == 0u || width == 0u || height == 0u) return
        if (x >= mode.width || y >= mode.height) return

        val endX = clippedEnd(x, width, mode.width)
        val endY = clippedEnd(y, height, mode.height)
        var dy = y
        while (dy < endY) {
            val sy = ((dy - y).toULong() * sourceHeight.toULong() / height.toULong()).toUInt()
            var dx = x
            while (dx < endX) {
                val sx = ((dx - x).toULong() * sourceWidth.toULong() / width.toULong()).toUInt()
                val sourceIndex = (sy * sourceWidth + sx).toInt()
                val paletteIndex = pixels[sourceIndex].toInt() and 0xff
                val color = if (paletteIndex < palette.size) palette[paletteIndex] else 0u
                RawMemory.write32(
                    buffer.physicalAddress + dy.toULong() * mode.strideBytes.toULong() + dx.toULong() * 4UL,
                    color,
                )
                dx++
            }
            dy++
        }
        markDirty(x, y, endX - x, endY - y)
    }

    fun present(): Boolean {
        if (!hasDirtyRect()) return true

        val ok = device.present(dirtyMinX, dirtyMinY, dirtyMaxX - dirtyMinX, dirtyMaxY - dirtyMinY)
        if (ok) clearDirty()
        return ok
    }

    fun presentAll(): Boolean {
        val ok = device.present(0u, 0u, mode.width, mode.height)
        if (ok) clearDirty()
        return ok
    }

    private fun markDirty(x: UInt, y: UInt, width: UInt, height: UInt) {
        if (width == 0u || height == 0u) return

        val endX = clippedEnd(x, width, mode.width)
        val endY = clippedEnd(y, height, mode.height)
        if (!hasDirtyRect()) {
            dirtyMinX = x
            dirtyMinY = y
            dirtyMaxX = endX
            dirtyMaxY = endY
            return
        }

        dirtyMinX = minOf(dirtyMinX, x)
        dirtyMinY = minOf(dirtyMinY, y)
        dirtyMaxX = maxOf(dirtyMaxX, endX)
        dirtyMaxY = maxOf(dirtyMaxY, endY)
    }

    private fun hasDirtyRect(): Boolean = dirtyMaxX > dirtyMinX && dirtyMaxY > dirtyMinY

    private fun clearDirty() {
        dirtyMinX = 0u
        dirtyMinY = 0u
        dirtyMaxX = 0u
        dirtyMaxY = 0u
    }

    private fun clippedEnd(start: UInt, size: UInt, limit: UInt): UInt {
        val available = limit - start
        return start + minOf(size, available)
    }
}