package drivers.storage

import drivers.DeviceCapability
import drivers.DeviceManager
import drivers.DriverBinding
import drivers.HardwareDevice
import drivers.KernelDriver
import drivers.virtio.VirtQueue
import drivers.virtio.VirtioMmioTransport
import fdt.DeviceTree
import hal.DmaBuffer
import hal.PageAllocator

private const val VIRTIO_BLOCK_DEVICE_ID: UInt = 2u
private const val VIRTIO_BLK_T_IN: UInt = 0u
private const val VIRTIO_BLK_T_OUT: UInt = 1u
private const val SECTOR_SIZE: UInt = 512u

interface BlockDevice : DeviceCapability {
    val capacityBytes: ULong
    fun readBytes(offset: ULong, length: UInt): ByteArray?
    fun writeBytes(offset: ULong, bytes: ByteArray): Boolean
}

object BlockStorageService {
    private var lastStatus = "not initialized"

    fun initialize(deviceTree: DeviceTree?): Boolean {
        DeviceManager.initialize(deviceTree)
        val devices = DeviceManager.blockDevices()
        lastStatus = if (devices.isEmpty()) "no block devices" else devices.joinToString { "${it.name}:${it.capacityBytes} bytes" }
        return devices.isNotEmpty()
    }

    fun status(): String = lastStatus

    fun readBytes(offset: ULong, length: UInt): ByteArray? {
        val block = DeviceManager.blockDevices().firstOrNull() ?: return null
        return block.readBytes(offset, length)
    }

    fun writeBytes(offset: ULong, bytes: ByteArray): Boolean {
        val block = DeviceManager.blockDevices().firstOrNull() ?: return false
        return block.writeBytes(offset, bytes)
    }
}

object VirtioBlockDriver : KernelDriver {
    override val name: String = "virtio-blk"

    override fun supports(device: HardwareDevice): Boolean =
        (device as? HardwareDevice.VirtioMmio)?.transport?.deviceId == VIRTIO_BLOCK_DEVICE_ID

    override fun bind(device: HardwareDevice): DriverBinding? {
        val transport = (device as? HardwareDevice.VirtioMmio)?.transport ?: return null
        val block = VirtioBlockDevice(transport)
        if (!block.initialize()) {
            return DriverBinding(transport.name, name, block.status, emptyList())
        }
        return DriverBinding(transport.name, name, block.status, listOf(block))
    }
}

class VirtioBlockDevice(private val transport: VirtioMmioTransport) : BlockDevice {
    override val name: String = transport.name
    var status: String = "not initialized"
        private set
    var capacitySectors: ULong = 0UL
        private set
    override val capacityBytes: ULong get() = capacitySectors * SECTOR_SIZE.toULong()

    private lateinit var queue: VirtQueue
    private lateinit var request: DmaBuffer
    private lateinit var data: DmaBuffer
    private lateinit var requestStatus: DmaBuffer

    fun initialize(): Boolean {
        capacitySectors = transport.readConfig32(0UL).toULong() or (transport.readConfig32(4UL).toULong() shl 32)
        if (capacitySectors == 0UL) {
            status = "empty virtio-blk device"
            return false
        }

        if (!transport.initializeNoFeatures()) {
            status = "feature negotiation failed"
            return false
        }

        queue = transport.createQueue(0u, 8u)
        transport.driverOk()

        request = PageAllocator.allocateBytes(16u)
        data = PageAllocator.allocateBytes(SECTOR_SIZE)
        requestStatus = PageAllocator.allocateBytes(1u)
        status = "ready"
        return true
    }

    override fun readBytes(offset: ULong, length: UInt): ByteArray? {
        if (length == 0u) return ByteArray(0)

        val output = ByteArray(length.toInt())
        var copied = 0u
        var cursor = offset
        while (copied < length) {
            val sector = cursor / SECTOR_SIZE.toULong()
            val sectorOffset = (cursor % SECTOR_SIZE.toULong()).toUInt()
            if (sector >= capacitySectors || !readSector(sector)) {
                return null
            }

            val available = SECTOR_SIZE - sectorOffset
            val step = minOf(available, length - copied)
            data.copyToBytes(output, copied.toInt(), sectorOffset, step)
            copied += step
            cursor += step.toULong()
        }
        return output
    }

    override fun writeBytes(offset: ULong, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true

        var copied = 0u
        var cursor = offset
        val length = bytes.size.toUInt()
        while (copied < length) {
            val sector = cursor / SECTOR_SIZE.toULong()
            val sectorOffset = (cursor % SECTOR_SIZE.toULong()).toUInt()
            if (sector >= capacitySectors) return false

            if (sectorOffset != 0u || length - copied < SECTOR_SIZE) {
                if (!readSector(sector)) return false
            } else {
                data.zero()
            }

            val available = SECTOR_SIZE - sectorOffset
            val step = minOf(available, length - copied)
            data.copyFromBytes(bytes, copied.toInt(), sectorOffset, step)
            if (!writeSector(sector)) return false

            copied += step
            cursor += step.toULong()
        }
        return true
    }

    private fun readSector(sector: ULong): Boolean {
        request.zero()
        request.write32(0u, VIRTIO_BLK_T_IN)
        request.write32(4u, 0u)
        request.write64(8u, sector)
        requestStatus.write8(0u, 0xffu)

        val ok = queue.submitRead(request, 16u, data, SECTOR_SIZE, requestStatus)
        if (!ok) {
            status = "virtqueue timeout"
            return false
        }
        if (requestStatus.read8(0u) != 0.toUByte()) {
            status = "read failed at sector $sector"
            return false
        }
        return true
    }

    private fun writeSector(sector: ULong): Boolean {
        request.zero()
        request.write32(0u, VIRTIO_BLK_T_OUT)
        request.write32(4u, 0u)
        request.write64(8u, sector)
        requestStatus.write8(0u, 0xffu)

        val ok = queue.submitWrite(request, 16u, data, SECTOR_SIZE, requestStatus)
        if (!ok) {
            status = "virtqueue timeout"
            return false
        }
        if (requestStatus.read8(0u) != 0.toUByte()) {
            status = "write failed at sector $sector"
            return false
        }
        return true
    }
}
