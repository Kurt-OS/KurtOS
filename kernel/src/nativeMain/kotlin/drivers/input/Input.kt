package drivers.input

import drivers.DeviceManager
import drivers.DriverBinding
import drivers.HardwareDevice
import drivers.KernelDriver
import fdt.DeviceTree

enum class InputDeviceKind {
    Keyboard,
    Mouse,
    Unknown,
}

data class KeyboardEvent(val code: UShort, val pressed: Boolean)

data class MouseSnapshot(
    val x: Int,
    val y: Int,
    val deltaX: Int,
    val deltaY: Int,
    val left: Boolean,
    val right: Boolean,
    val middle: Boolean,
)

object InputService {
    private val devices = mutableListOf<InputPort>()
    private val keyStates = BooleanArray(512)
    private val keyboardEvents = ArrayDeque<KeyboardEvent>()
    private var initialized = false
    private var lastStatus = "not initialized"

    private var mouseX = 0
    private var mouseY = 0
    private var mouseDeltaX = 0
    private var mouseDeltaY = 0
    private var mouseLeft = false
    private var mouseRight = false
    private var mouseMiddle = false

    fun initialize(deviceTree: DeviceTree?): Boolean {
        if (initialized) return devices.isNotEmpty()

        DeviceManager.initialize(deviceTree)
        devices.addAll(DeviceManager.inputPorts())

        initialized = true
        lastStatus = if (devices.isEmpty()) {
            "no input devices"
        } else {
            devices.joinToString { "${it.kind.name.lowercase()}:${it.name}" }
        }
        return devices.isNotEmpty()
    }

    fun status(): String = lastStatus

    fun poll() {
        devices.forEach { device ->
            device.poll { event ->
                handleEvent(device.kind, event)
            }
        }
    }

    fun isKeyDown(code: UShort): Boolean =
        code.toInt().let { it in keyStates.indices && keyStates[it] }

    fun nextKeyboardEvent(): KeyboardEvent? =
        if (keyboardEvents.isEmpty()) null else keyboardEvents.removeFirst()

    fun mouseSnapshot(resetDelta: Boolean = true): MouseSnapshot {
        val snapshot = MouseSnapshot(
            mouseX,
            mouseY,
            mouseDeltaX,
            mouseDeltaY,
            mouseLeft,
            mouseRight,
            mouseMiddle,
        )
        if (resetDelta) {
            mouseDeltaX = 0
            mouseDeltaY = 0
        }
        return snapshot
    }

    private fun handleEvent(kind: InputDeviceKind, event: VirtioInputEvent) {
        when (event.type) {
            InputConstants.EV_KEY -> handleKey(event.code, event.value != 0u)
            InputConstants.EV_REL -> handleRelativeMouse(event.code, event.value)
            InputConstants.EV_ABS -> handleAbsoluteMouse(kind, event.code, event.value)
            InputConstants.EV_SYN -> Unit
        }
    }

    private fun handleKey(code: UShort, pressed: Boolean) {
        val index = code.toInt()
        if (index in keyStates.indices) {
            keyStates[index] = pressed
            keyboardEvents.addLast(KeyboardEvent(code, pressed))
        }
        when (code) {
            InputConstants.BTN_LEFT -> mouseLeft = pressed
            InputConstants.BTN_RIGHT -> mouseRight = pressed
            InputConstants.BTN_MIDDLE -> mouseMiddle = pressed
        }
    }

    private fun handleRelativeMouse(code: UShort, value: UInt) {
        val signed = value.toInt()
        when (code) {
            InputConstants.REL_X -> {
                mouseDeltaX += signed
                mouseX += signed
            }
            InputConstants.REL_Y -> {
                mouseDeltaY += signed
                mouseY += signed
            }
        }
    }

    private fun handleAbsoluteMouse(kind: InputDeviceKind, code: UShort, value: UInt) {
        if (kind != InputDeviceKind.Mouse) return
        when (code) {
            InputConstants.ABS_X -> mouseX = value.toInt()
            InputConstants.ABS_Y -> mouseY = value.toInt()
        }
    }
}

object VirtioInputDriver : KernelDriver {
    override val name: String = "virtio-input"

    override fun supports(device: HardwareDevice): Boolean =
        (device as? HardwareDevice.VirtioMmio)?.transport?.deviceId == InputConstants.VIRTIO_INPUT_DEVICE_ID

    override fun bind(device: HardwareDevice): DriverBinding? {
        val transport = (device as? HardwareDevice.VirtioMmio)?.transport ?: return null
        val input = VirtioInputDevice(transport)
        val ok = input.initialize()
        return DriverBinding(transport.name, name, if (ok) "ready ${input.name}" else "initialize failed", if (ok) listOf(input) else emptyList())
    }
}

