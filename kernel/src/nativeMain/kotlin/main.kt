package kernel

import hal.Memory
import hal.UART
import kernel.drivers.DeviceManager
import kernel.fdt.DeviceTree
import kernel.memory.PageAllocator
import kernel.shell.KernelShell
import shell.CommandRegistry
import shell.Shell
import userspace.Userspace
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName

@OptIn(ExperimentalNativeApi::class)
@CName("kotlin_main")
fun main() {
    UART.println("What's up with it, vanilla face?")

    Memory.reportHeapInfo()
    val deviceTree = DeviceTree.current()
    PageAllocator.initialize(deviceTree?.range)
    if (deviceTree == null) {
        UART.println("FDT: unavailable")
    } else {
        UART.println("FDT: base=0x${deviceTree.range.start.toString(16)} size=${deviceTree.totalSize} bytes")
    }
    UART.println("Pages: base=0x${PageAllocator.managedRange.start.toString(16)} size=${PageAllocator.managedRange.size} bytes")
    DeviceManager.initialize(deviceTree)
    DeviceManager.printSummary()

    val registry = CommandRegistry()
    Userspace.install(registry)
    KernelShell.install(registry, deviceTree)
    Shell.run(registry)
}
