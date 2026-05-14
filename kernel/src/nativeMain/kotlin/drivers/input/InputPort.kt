package drivers.input

import kernel.drivers.DeviceCapability
import kernel.drivers.input.InputDeviceKind

interface InputPort : DeviceCapability {
    val kind: InputDeviceKind
    fun poll(dispatch: (VirtioInputEvent) -> Unit)
}