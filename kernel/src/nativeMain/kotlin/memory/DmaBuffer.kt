package kernel.memory

import hal.RawMemory

class DmaBuffer internal constructor(
    val physicalAddress: ULong,
    val size: UInt,
    private val pageCount: Int,
) {
    fun zero() {
        RawMemory.zero(physicalAddress, size)
    }

    fun write8(offset: UInt, value: UByte) {
        RawMemory.write8(physicalAddress + offset.toULong(), value)
    }

    fun write16(offset: UInt, value: UShort) {
        RawMemory.write16(physicalAddress + offset.toULong(), value)
    }

    fun write32(offset: UInt, value: UInt) {
        RawMemory.write32(physicalAddress + offset.toULong(), value)
    }

    fun write64(offset: UInt, value: ULong) {
        RawMemory.write64(physicalAddress + offset.toULong(), value)
    }

    fun read8(offset: UInt): UByte = RawMemory.read8(physicalAddress + offset.toULong())

    fun read16(offset: UInt): UShort = RawMemory.read16(physicalAddress + offset.toULong())

    fun read32(offset: UInt): UInt = RawMemory.read32(physicalAddress + offset.toULong())

    fun read64(offset: UInt): ULong = RawMemory.read64(physicalAddress + offset.toULong())

    fun copyToBytes(target: ByteArray, targetOffset: Int = 0, sourceOffset: UInt = 0u, length: UInt = size) {
        var i = 0u
        while (i < length) {
            target[targetOffset + i.toInt()] = read8(sourceOffset + i).toByte()
            i++
        }
    }

    internal fun pages(): Int = pageCount
}