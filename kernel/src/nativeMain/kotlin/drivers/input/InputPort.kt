package drivers.input

import drivers.DeviceCapability

interface InputPort : DeviceCapability {
    val kind: InputDeviceKind
    fun poll(dispatch: (VirtioInputEvent) -> Unit)
}