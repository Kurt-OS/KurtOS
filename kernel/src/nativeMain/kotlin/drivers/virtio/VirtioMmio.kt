package kernel.drivers.virtio

import hal.Platform_QEMU
import hal.RawMemory
import kernel.fdt.DeviceTree
import kernel.memory.DmaBuffer
import kernel.memory.PageAllocator

private const val VIRTIO_MMIO_MAGIC: UInt = 0x74726976u

private const val REG_MAGIC: ULong = 0x000UL
private const val REG_VERSION: ULong = 0x004UL
private const val REG_DEVICE_ID: ULong = 0x008UL
private const val REG_DEVICE_FEATURES_SEL: ULong = 0x014UL
private const val REG_DRIVER_FEATURES: ULong = 0x020UL
private const val REG_DRIVER_FEATURES_SEL: ULong = 0x024UL
private const val REG_QUEUE_SEL: ULong = 0x030UL
private const val REG_QUEUE_NUM_MAX: ULong = 0x034UL
private const val REG_QUEUE_NUM: ULong = 0x038UL
private const val REG_QUEUE_READY: ULong = 0x044UL
private const val REG_QUEUE_NOTIFY: ULong = 0x050UL
private const val REG_INTERRUPT_STATUS: ULong = 0x060UL
private const val REG_INTERRUPT_ACK: ULong = 0x064UL
private const val REG_STATUS: ULong = 0x070UL
private const val REG_QUEUE_DESC_LOW: ULong = 0x080UL
private const val REG_QUEUE_DESC_HIGH: ULong = 0x084UL
private const val REG_QUEUE_DRIVER_LOW: ULong = 0x090UL
private const val REG_QUEUE_DRIVER_HIGH: ULong = 0x094UL
private const val REG_QUEUE_DEVICE_LOW: ULong = 0x0A0UL
private const val REG_QUEUE_DEVICE_HIGH: ULong = 0x0A4UL
private const val REG_CONFIG: ULong = 0x100UL

private const val STATUS_ACKNOWLEDGE: UInt = 1u
private const val STATUS_DRIVER: UInt = 2u
private const val STATUS_DRIVER_OK: UInt = 4u
private const val STATUS_FEATURES_OK: UInt = 8u
private const val STATUS_FAILED: UInt = 128u

class VirtioMmioTransport(val name: String, val base: ULong) {

    val deviceId: UInt get() = read(REG_DEVICE_ID)
    val version: UInt get() = read(REG_VERSION)

    fun isValid(): Boolean = read(REG_MAGIC) == VIRTIO_MMIO_MAGIC && version >= 2u && deviceId != 0u

    fun initializeNoFeatures(): Boolean {
        write(REG_STATUS, 0u)
        setStatus(STATUS_ACKNOWLEDGE)
        setStatus(STATUS_DRIVER)

        write(REG_DEVICE_FEATURES_SEL, 0u)
        write(REG_DRIVER_FEATURES_SEL, 0u)
        write(REG_DRIVER_FEATURES, 0u)
        write(REG_DEVICE_FEATURES_SEL, 1u)
        write(REG_DRIVER_FEATURES_SEL, 1u)
        write(REG_DRIVER_FEATURES, 0u)

        setStatus(STATUS_FEATURES_OK)
        if (read(REG_STATUS) and STATUS_FEATURES_OK == 0u) {
            fail()
            return false
        }

        return true
    }

    fun createQueue(index: UInt, requestedSize: UInt): VirtQueue {
        write(REG_QUEUE_SEL, index)
        val max = read(REG_QUEUE_NUM_MAX)
        require(max > 0u) { "Virtio queue $index unavailable" }
        val size = minOf(max, requestedSize)

        val descriptors = PageAllocator.allocateBytes(size * 16u)
        val avail = PageAllocator.allocateBytes(6u + size * 2u)
        val used = PageAllocator.allocateBytes(6u + size * 8u)

        write(REG_QUEUE_NUM, size)
        writeAddress(REG_QUEUE_DESC_LOW, descriptors.physicalAddress)
        writeAddress(REG_QUEUE_DRIVER_LOW, avail.physicalAddress)
        writeAddress(REG_QUEUE_DEVICE_LOW, used.physicalAddress)
        write(REG_QUEUE_READY, 1u)

        return VirtQueue(this, index, size.toUShort(), descriptors, avail, used)
    }

    fun driverOk() {
        setStatus(STATUS_DRIVER_OK)
    }

    fun notifyQueue(index: UInt) {
        write(REG_QUEUE_NOTIFY, index)
    }

    fun ackInterrupts() {
        val status = read(REG_INTERRUPT_STATUS)
        if (status != 0u) write(REG_INTERRUPT_ACK, status)
    }

    fun readConfig8(offset: ULong): UByte = RawMemory.read8(base + REG_CONFIG + offset)

    fun readConfig16(offset: ULong): UShort = RawMemory.read16(base + REG_CONFIG + offset)

    fun readConfig32(offset: ULong): UInt = RawMemory.read32(base + REG_CONFIG + offset)

    fun writeConfig8(offset: ULong, value: UByte) {
        RawMemory.write8(base + REG_CONFIG + offset, value)
    }

    fun fail() {
        setStatus(STATUS_FAILED)
    }

    private fun setStatus(bits: UInt) {
        write(REG_STATUS, read(REG_STATUS) or bits)
    }

    private fun writeAddress(lowRegister: ULong, address: ULong) {
        write(lowRegister, address.toUInt())
        write(lowRegister + 4UL, (address shr 32).toUInt())
    }

    private fun read(offset: ULong): UInt = RawMemory.read32(base + offset)

    private fun write(offset: ULong, value: UInt) {
        RawMemory.write32(base + offset, value)
    }
}

data class UsedElement(val id: UInt, val length: UInt)

class VirtQueue internal constructor(
    private val transport: VirtioMmioTransport,
    private val index: UInt,
    private val size: UShort,
    private val descriptors: DmaBuffer,
    private val avail: DmaBuffer,
    private val used: DmaBuffer,
) {
    private var availIndex: UShort = 0u
    private var lastUsedIndex: UShort = 0u

    val capacity: UInt get() = size.toUInt()

    fun submit(command: DmaBuffer, commandLength: UInt, response: DmaBuffer, responseLength: UInt): Boolean {
        writeDescriptor(0u, command.physicalAddress, commandLength, flags = 1u, next = 1u)
        writeDescriptor(1u, response.physicalAddress, responseLength, flags = 2u, next = 0u)

        return submitHead(0u)
    }

    fun submitRead(command: DmaBuffer, commandLength: UInt, data: DmaBuffer, dataLength: UInt, status: DmaBuffer): Boolean {
        writeDescriptor(0u, command.physicalAddress, commandLength, flags = 1u, next = 1u)
        writeDescriptor(1u, data.physicalAddress, dataLength, flags = 3u, next = 2u)
        writeDescriptor(2u, status.physicalAddress, 1u, flags = 2u, next = 0u)

        return submitHead(0u)
    }

    private fun submitHead(head: UShort): Boolean {
        val ringOffset = 4u + ((availIndex.toUInt() % size.toUInt()) * 2u)
        avail.write16(ringOffset, head)
        availIndex = (availIndex + 1u).toUShort()
        avail.write16(2u, availIndex)

        transport.notifyQueue(index)

        var spins = 0
        while (used.read16(2u) == lastUsedIndex) {
            spins++
            if (spins > 10_000_000) return false
        }
        lastUsedIndex = used.read16(2u)
        transport.ackInterrupts()
        return true
    }

    fun postReceive(buffer: DmaBuffer, length: UInt) {
        postReceiveDescriptor(0u, buffer, length)
    }

    fun postReceiveDescriptor(id: UInt, buffer: DmaBuffer, length: UInt) {
        require(id < capacity) { "descriptor id out of range" }
        writeDescriptor(id, buffer.physicalAddress, length, flags = 2u, next = 0u)

        val ringOffset = 4u + ((availIndex.toUInt() % size.toUInt()) * 2u)
        avail.write16(ringOffset, id.toUShort())
        availIndex = (availIndex + 1u).toUShort()
        avail.write16(2u, availIndex)

        transport.notifyQueue(index)
    }

    fun consumeUsedLength(): UInt? {
        return consumeUsed()?.length
    }

    fun consumeUsed(): UsedElement? {
        val usedIndex = used.read16(2u)
        if (usedIndex == lastUsedIndex) return null

        val ringOffset = 4u + ((lastUsedIndex.toUInt() % size.toUInt()) * 8u)
        val id = used.read32(ringOffset)
        val length = used.read32(ringOffset + 4u)
        lastUsedIndex = (lastUsedIndex + 1u).toUShort()
        transport.ackInterrupts()
        return UsedElement(id, length)
    }

    private fun writeDescriptor(id: UInt, address: ULong, length: UInt, flags: UShort, next: UShort) {
        val offset = id * 16u
        descriptors.write64(offset, address)
        descriptors.write32(offset + 8u, length)
        descriptors.write16(offset + 12u, flags)
        descriptors.write16(offset + 14u, next)
    }
}

object VirtioMmioBus {
    fun discover(deviceTree: DeviceTree?): List<VirtioMmioTransport> {
        val fromTree = deviceTree?.findVirtioMmioDevices().orEmpty().map {
            VirtioMmioTransport(it.name, it.base)
        }.filter { it.isValid() }
        if (fromTree.isNotEmpty()) return fromTree

        val devices = mutableListOf<VirtioMmioTransport>()
        for (i in 0 until Platform_QEMU.VIRTIO_MMIO_COUNT) {
            val base = Platform_QEMU.VIRTIO_MMIO_BASE + i.toULong() * Platform_QEMU.VIRTIO_MMIO_STRIDE
            val transport = VirtioMmioTransport("virtio-mmio@$i", base)
            if (transport.isValid()) devices.add(transport)
        }
        return devices
    }
}
