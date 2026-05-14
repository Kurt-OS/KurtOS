package kernel.drivers

import drivers.input.InputPort
import hal.UART
import kernel.drivers.input.VirtioInputDriver
import kernel.drivers.storage.BlockDevice
import kernel.drivers.storage.VirtioBlockDriver
import kernel.drivers.virtio.VirtioMmioBus
import kernel.drivers.virtio.VirtioMmioTransport
import kernel.fdt.DeviceTree
import kernel.graphics.DisplayDevice
import kernel.graphics.VirtioGpuDriver

sealed class HardwareDevice {
    data class VirtioMmio(val transport: VirtioMmioTransport) : HardwareDevice()
}

interface DeviceCapability {
    val name: String
}

data class DriverBinding(
    val deviceName: String,
    val driverName: String,
    val status: String,
    val capabilities: List<DeviceCapability>,
)

interface KernelDriver {
    val name: String
    fun supports(device: HardwareDevice): Boolean
    fun bind(device: HardwareDevice): DriverBinding?
}

object DeviceManager {
    private val drivers: List<KernelDriver> = listOf(
        VirtioBlockDriver,
        VirtioGpuDriver,
        VirtioInputDriver,
    )
    private val hardware = mutableListOf<HardwareDevice>()
    private val bindings = mutableListOf<DriverBinding>()
    private val failedBindings = mutableListOf<DriverBinding>()
    private var initialized = false

    fun initialize(deviceTree: DeviceTree?) {
        if (initialized) return
        initialized = true

        discoverHardware(deviceTree)
        bindDrivers()
    }

    fun printSummary() {
        UART.println("Devices: ${hardware.size} hardware nodes, ${bindings.size} bound drivers")
        if (hardware.isEmpty()) {
            UART.println("Devices: no hardware discovered")
        }
        bindings.forEach { binding ->
            val caps = binding.capabilities.joinToString { it.name }.ifBlank { "no capabilities" }
            UART.println("${binding.deviceName}: ${binding.driverName} (${binding.status}) [$caps]")
        }
        failedBindings.forEach { binding ->
            UART.println("${binding.deviceName}: ${binding.driverName} failed (${binding.status})")
        }
    }

    fun blockDevices(): List<BlockDevice> = capabilitiesOf()

    fun displayDevices(): List<DisplayDevice> = capabilitiesOf()

    fun inputPorts(): List<InputPort> = capabilitiesOf()

    fun virtioDevices(): List<VirtioMmioTransport> =
        hardware.mapNotNull { (it as? HardwareDevice.VirtioMmio)?.transport }

    private fun discoverHardware(deviceTree: DeviceTree?) {
        VirtioMmioBus.discover(deviceTree).forEach { transport ->
            hardware.add(HardwareDevice.VirtioMmio(transport))
        }
    }

    private fun bindDrivers() {
        hardware.forEach { device ->
            val driver = drivers.firstOrNull { it.supports(device) }
            if (driver == null) return@forEach

            val binding = driver.bind(device)
            if (binding == null) {
                failedBindings.add(DriverBinding(deviceName(device), driver.name, "bind returned null", emptyList()))
            } else {
                bindings.add(binding)
            }
        }
    }

    private inline fun <reified T : DeviceCapability> capabilitiesOf(): List<T> =
        bindings.flatMap { it.capabilities }.filterIsInstance<T>()

    private fun deviceName(device: HardwareDevice): String =
        when (device) {
            is HardwareDevice.VirtioMmio -> device.transport.name
        }
}
