package kernel.graphics

import graphics.GraphicsConstants
import kernel.drivers.DriverBinding
import kernel.drivers.HardwareDevice
import kernel.drivers.KernelDriver

object VirtioGpuDriver : KernelDriver {
    override val name: String = "virtio-gpu"

    override fun supports(device: HardwareDevice): Boolean =
        (device as? HardwareDevice.VirtioMmio)?.transport?.deviceId == GraphicsConstants.VIRTIO_GPU_DEVICE_ID

    override fun bind(device: HardwareDevice): DriverBinding? {
        val transport = (device as? HardwareDevice.VirtioMmio)?.transport ?: return null
        val gpu = VirtioGpuDevice(transport)
        val framebuffer = gpu.initialize()
        val capabilities = if (framebuffer == null) emptyList() else listOf(gpu)
        return DriverBinding(transport.name, name, gpu.status, capabilities)
    }
}