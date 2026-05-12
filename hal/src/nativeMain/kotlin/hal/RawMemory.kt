package hal

import kotlinx.cinterop.ExperimentalForeignApi
import mmio.raw_read16
import mmio.raw_read64
import mmio.raw_read8
import mmio.raw_write16
import mmio.raw_write64
import mmio.raw_write8

@OptIn(ExperimentalForeignApi::class)
object RawMemory {

    fun read8(address: ULong): UByte = raw_read8(address)

    fun read16(address: ULong): UShort = raw_read16(address)

    fun read32(address: ULong): UInt = mmio.mmio_read32(address)

    fun read64(address: ULong): ULong = raw_read64(address)

    fun write8(address: ULong, value: UByte) {
        raw_write8(address, value)
    }

    fun write16(address: ULong, value: UShort) {
        raw_write16(address, value)
    }

    fun write32(address: ULong, value: UInt) {
        mmio.mmio_write32(address, value)
    }

    fun write64(address: ULong, value: ULong) {
        raw_write64(address, value)
    }

    fun zero(address: ULong, length: UInt) {
        var offset = 0u
        while (offset < length) {
            write8(address + offset.toULong(), 0u)
            offset++
        }
    }

    fun copyFromBytes(address: ULong, data: ByteArray) {
        for (i in data.indices) {
            write8(address + i.toULong(), data[i].toUByte())
        }
    }

    fun writeAscii(address: ULong, text: String, maxBytes: Int) {
        val limit = minOf(text.length, maxBytes)
        for (i in 0 until limit) {
            write8(address + i.toULong(), text[i].code.toUByte())
        }
    }
}
