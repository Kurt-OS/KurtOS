package fdt

import hal.BootInfo
import hal.RawMemory
import memory.PhysicalRange

data class VirtioMmioDevice(
    val name: String,
    val base: ULong,
    val size: ULong,
)

class DeviceTree private constructor(
    private val base: ULong,
    val totalSize: UInt,
) {
    val range: PhysicalRange get() = PhysicalRange(base, totalSize.toULong())

    fun findVirtioMmioDevices(): List<VirtioMmioDevice> {
        if (base == 0UL) return emptyList()

        val structOffset = readHeader32(8u)
        val stringsOffset = readHeader32(12u)
        val structEnd = base + structOffset.toULong() + readHeader32(36u).toULong()
        var cursor = base + structOffset.toULong()
        val stack = mutableListOf<NodeState>()
        val devices = mutableListOf<VirtioMmioDevice>()

        while (cursor < structEnd) {
            when (val token = readBe32(cursor)) {
                FDT_BEGIN_NODE -> {
                    cursor += 4UL
                    val name = readCString(cursor)
                    cursor = align4(cursor + name.length.toULong() + 1UL)
                    stack.add(NodeState(name))
                }
                FDT_END_NODE -> {
                    val node = stack.removeLastOrNull()
                    val regBase = node?.regBase
                    if (node != null && node.compatible.any { it == "virtio,mmio" } && regBase != null) {
                        devices.add(VirtioMmioDevice(node.name, regBase, node.regSize ?: 0UL))
                    }
                    cursor += 4UL
                }
                FDT_PROP -> {
                    val length = readBe32(cursor + 4UL)
                    val nameOffset = readBe32(cursor + 8UL)
                    val data = cursor + 12UL
                    val propertyName = readCString(base + stringsOffset.toULong() + nameOffset.toULong())
                    val node = stack.lastOrNull()
                    if (node != null) {
                        applyProperty(node, propertyName, data, length)
                    }
                    cursor = align4(data + length.toULong())
                }
                FDT_NOP -> cursor += 4UL
                FDT_END -> return devices
                else -> error("Unsupported FDT token 0x${token.toString(16)} at 0x${cursor.toString(16)}")
            }
        }

        return devices
    }

    private fun applyProperty(node: NodeState, name: String, data: ULong, length: UInt) {
        when (name) {
            "compatible" -> node.compatible = readStringList(data, length)
            "reg" -> {
                if (length >= 16u) {
                    val addrHigh = readBe32(data).toULong()
                    val addrLow = readBe32(data + 4UL).toULong()
                    val sizeHigh = readBe32(data + 8UL).toULong()
                    val sizeLow = readBe32(data + 12UL).toULong()
                    node.regBase = (addrHigh shl 32) or addrLow
                    node.regSize = (sizeHigh shl 32) or sizeLow
                }
            }
        }
    }

    private fun readHeader32(offset: UInt): UInt = readBe32(base + offset.toULong())

    private class NodeState(val name: String) {
        var compatible: List<String> = emptyList()
        var regBase: ULong? = null
        var regSize: ULong? = null
    }

    companion object {
        fun current(): DeviceTree? {
            val fdt = BootInfo.fdtBase
            if (fdt == 0UL || readBe32(fdt) != FDT_MAGIC) return null
            return DeviceTree(fdt, readBe32(fdt + 4UL))
        }
    }
}

private const val FDT_MAGIC: UInt = 0xd00dfeedu
private const val FDT_BEGIN_NODE: UInt = 1u
private const val FDT_END_NODE: UInt = 2u
private const val FDT_PROP: UInt = 3u
private const val FDT_NOP: UInt = 4u
private const val FDT_END: UInt = 9u

private fun readBe32(address: ULong): UInt {
    val b0 = RawMemory.read8(address).toUInt()
    val b1 = RawMemory.read8(address + 1UL).toUInt()
    val b2 = RawMemory.read8(address + 2UL).toUInt()
    val b3 = RawMemory.read8(address + 3UL).toUInt()
    return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
}

private fun readCString(address: ULong): String {
    val chars = StringBuilder()
    var cursor = address
    while (true) {
        val b = RawMemory.read8(cursor)
        if (b == 0.toUByte()) break
        chars.append(b.toInt().toChar())
        cursor++
    }
    return chars.toString()
}

private fun readStringList(address: ULong, length: UInt): List<String> {
    val values = mutableListOf<String>()
    val current = StringBuilder()
    var offset = 0u
    while (offset < length) {
        val b = RawMemory.read8(address + offset.toULong())
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

private fun align4(value: ULong): ULong = (value + 3UL) and 3UL.inv()
