package drivers.input

import drivers.virtio.VirtQueue
import drivers.virtio.VirtioMmioTransport
import memory.DmaBuffer
import memory.PageAllocator

data class VirtioInputEvent(
    val type: UShort,
    val code: UShort,
    val value: UInt,
)

internal class VirtioInputDevice(private val transport: VirtioMmioTransport) : InputPort {
    override var name: String = transport.name
        private set
    override var kind: InputDeviceKind = InputDeviceKind.Unknown
        private set

    private lateinit var eventQueue: VirtQueue
    private lateinit var eventBuffers: Array<DmaBuffer>

    fun initialize(): Boolean {
        name = readName().ifBlank { transport.name }
        kind = inferKind(name)

        if (!transport.initializeNoFeatures()) {
            return false
        }

        eventQueue = transport.createQueue(0u, 8u)
        transport.createQueue(1u, 4u)

        transport.driverOk()
        eventBuffers = Array(minOf(InputConstants.INPUT_EVENT_BUFFERS, eventQueue.capacity.toInt())) {
            PageAllocator.allocateBytes(InputConstants.INPUT_EVENT_SIZE)
        }
        for (descriptor in eventBuffers.indices) {
            eventQueue.postReceiveDescriptor(descriptor.toUInt(), eventBuffers[descriptor], InputConstants.INPUT_EVENT_SIZE)
        }
        return true
    }

    override fun poll(dispatch: (VirtioInputEvent) -> Unit) {
        while (true) {
            val used = eventQueue.consumeUsed() ?: break
            val buffer = eventBuffers.getOrNull(used.id.toInt()) ?: continue
            if (used.length >= InputConstants.INPUT_EVENT_SIZE) {
                dispatch(
                    VirtioInputEvent(
                        buffer.read16(0u),
                        buffer.read16(2u),
                        buffer.read32(4u),
                    )
                )
            }
            buffer.zero()
            eventQueue.postReceiveDescriptor(used.id, buffer, InputConstants.INPUT_EVENT_SIZE)
        }
    }

    private fun Array<DmaBuffer>.getOrNull(index: Int): DmaBuffer? {
        if (index !in indices) return null
        return this[index]
    }

    private fun readName(): String {
        transport.writeConfig8(0UL, InputConstants.VIRTIO_INPUT_CFG_ID_NAME)
        transport.writeConfig8(1UL, 0u)

        val size = minOf(transport.readConfig8(2UL).toInt(), 128)
        val chars = StringBuilder()
        for (i in 0 until size) {
            val byte = transport.readConfig8(8UL + i.toULong())
            if (byte == 0.toUByte()) break
            chars.append(byte.toInt().toChar())
        }
        return chars.toString()
    }

    private fun inferKind(name: String): InputDeviceKind {
        val lower = name.lowercase()
        return when {
            "keyboard" in lower -> InputDeviceKind.Keyboard
            "mouse" in lower || "tablet" in lower || "pointer" in lower -> InputDeviceKind.Mouse
            else -> InputDeviceKind.Unknown
        }
    }
}
