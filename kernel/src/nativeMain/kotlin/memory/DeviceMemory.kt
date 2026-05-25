package hal

object DeviceMemory {
    fun read8(address: ULong): UByte = RawMemory.read8(address)
    fun read16(address: ULong): UShort = RawMemory.read16(address)
    fun read32(address: ULong): UInt = RawMemory.read32(address)

    fun write8(address: ULong, value: UByte) {
        RawMemory.write8(address, value)
    }

    fun write32(address: ULong, value: UInt) {
        RawMemory.write32(address, value)
    }

    fun readBe32(address: ULong): UInt {
        val b0 = read8(address).toUInt()
        val b1 = read8(address + 1UL).toUInt()
        val b2 = read8(address + 2UL).toUInt()
        val b3 = read8(address + 3UL).toUInt()
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    fun readCString(address: ULong): String {
        val chars = StringBuilder()
        var cursor = address
        while (true) {
            val b = read8(cursor)
            if (b == 0.toUByte()) break
            chars.append(b.toInt().toChar())
            cursor++
        }
        return chars.toString()
    }

    fun readStringList(address: ULong, length: UInt): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var offset = 0u
        while (offset < length) {
            val b = read8(address + offset.toULong())
            if (b == 0.toUByte()) {
                if (current.isNotEmpty()) {
                    values.add(current.toString())
                    current.clear()
                }
            } else {
                current.append(b.toInt().toChar())
            }
            offset++
        }
        if (current.isNotEmpty()) values.add(current.toString())
        return values
    }
}

class MmioRegisters(private val base: ULong) {
    fun read8(offset: ULong): UByte = DeviceMemory.read8(base + offset)
    fun read16(offset: ULong): UShort = DeviceMemory.read16(base + offset)
    fun read32(offset: ULong): UInt = DeviceMemory.read32(base + offset)

    fun write8(offset: ULong, value: UByte) {
        DeviceMemory.write8(base + offset, value)
    }

    fun write32(offset: ULong, value: UInt) {
        DeviceMemory.write32(base + offset, value)
    }
}
