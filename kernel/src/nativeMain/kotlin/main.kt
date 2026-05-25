import hal.Memory
import hal.UART
import drivers.DeviceManager
import fdt.DeviceTree
import hal.PageAllocator
import shell.KernelShell
import shell.CommandRegistry
import shell.Shell
import userspace.UserspaceRuntime
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
    UserspaceRuntime.install(registry, deviceTree)
    KernelShell.install(registry, deviceTree)
    Shell.run(registry)
}
