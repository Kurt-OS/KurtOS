package fdt

import hal.BootInfo
import hal.DeviceMemory
import hal.PhysicalRange

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
            when (val token = DeviceMemory.readBe32(cursor)) {
                FDT_BEGIN_NODE -> {
                    cursor += 4UL
                    val name = DeviceMemory.readCString(cursor)
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
                    val length = DeviceMemory.readBe32(cursor + 4UL)
                    val nameOffset = DeviceMemory.readBe32(cursor + 8UL)
                    val data = cursor + 12UL
                    val propertyName = DeviceMemory.readCString(base + stringsOffset.toULong() + nameOffset.toULong())
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
            "compatible" -> node.compatible = DeviceMemory.readStringList(data, length)
            "reg" -> {
                if (length >= 16u) {
                    val addrHigh = DeviceMemory.readBe32(data).toULong()
                    val addrLow = DeviceMemory.readBe32(data + 4UL).toULong()
                    val sizeHigh = DeviceMemory.readBe32(data + 8UL).toULong()
                    val sizeLow = DeviceMemory.readBe32(data + 12UL).toULong()
                    node.regBase = (addrHigh shl 32) or addrLow
                    node.regSize = (sizeHigh shl 32) or sizeLow
                }
            }
        }
    }

    private fun readHeader32(offset: UInt): UInt = DeviceMemory.readBe32(base + offset.toULong())

    private class NodeState(val name: String) {
        var compatible: List<String> = emptyList()
        var regBase: ULong? = null
        var regSize: ULong? = null
    }

    companion object {
        fun current(): DeviceTree? {
            val fdt = BootInfo.fdtBase
            if (fdt == 0UL || DeviceMemory.readBe32(fdt) != FDT_MAGIC) return null
            return DeviceTree(fdt, DeviceMemory.readBe32(fdt + 4UL))
        }
    }
}

private const val FDT_MAGIC: UInt = 0xd00dfeedu
private const val FDT_BEGIN_NODE: UInt = 1u
private const val FDT_END_NODE: UInt = 2u
private const val FDT_PROP: UInt = 3u
private const val FDT_NOP: UInt = 4u
private const val FDT_END: UInt = 9u

private fun align4(value: ULong): ULong = (value + 3UL) and 3UL.inv()
