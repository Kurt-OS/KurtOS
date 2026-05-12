package hal

import kotlinx.cinterop.ExperimentalForeignApi
import mmio.boot_fdt_base
import mmio.kernel_heap_end
import mmio.kernel_heap_start

@OptIn(ExperimentalForeignApi::class)
object BootInfo {
    val fdtBase: ULong get() = boot_fdt_base()
    val heapStart: ULong get() = kernel_heap_start()
    val heapEnd: ULong get() = kernel_heap_end()
}
