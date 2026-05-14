package kernel.memory

import hal.BootInfo
import hal.Platform_QEMU

private const val PAGE_SIZE: UInt = 4096u

object PageAllocator {
    private var base: ULong = 0UL
    private var pages: BooleanArray = BooleanArray(0)
    private var initialized = false

    val pageSize: UInt get() = PAGE_SIZE
    val managedRange: PhysicalRange get() = PhysicalRange(base, pages.size.toULong() * PAGE_SIZE.toULong())

    fun initialize(fdtRange: PhysicalRange?) {
        if (initialized) return

        base = alignUp(BootInfo.heapEnd, PAGE_SIZE.toULong())
        val end = Platform_QEMU.RAM_END
        val pageCount = ((end - base) / PAGE_SIZE.toULong()).toInt()
        pages = BooleanArray(pageCount)

        if (fdtRange != null) {
            reserve(fdtRange)
        }

        initialized = true
    }

    fun allocatePage(): PhysicalPage {
        val buffer = allocatePages(1)
        return PhysicalPage(buffer.physicalAddress)
    }

    fun allocatePages(count: Int): DmaBuffer {
        require(count > 0) { "count must be positive" }
        ensureInitialized()

        var runStart = -1
        var runLength = 0
        for (i in pages.indices) {
            if (!pages[i]) {
                if (runStart == -1) runStart = i
                runLength++
                if (runLength == count) {
                    for (page in runStart until runStart + count) pages[page] = true
                    val address = base + runStart.toULong() * PAGE_SIZE.toULong()
                    val buffer = DmaBuffer(address, (count.toUInt() * PAGE_SIZE), count)
                    buffer.zero()
                    return buffer
                }
            } else {
                runStart = -1
                runLength = 0
            }
        }

        error("Out of physical pages")
    }

    fun allocateBytes(size: UInt): DmaBuffer {
        val count = ((size + PAGE_SIZE - 1u) / PAGE_SIZE).toInt()
        return allocatePages(count)
    }

    fun free(buffer: DmaBuffer) {
        ensureInitialized()
        val start = pageIndex(buffer.physicalAddress)
        for (i in start until start + buffer.pages()) {
            if (i in pages.indices) pages[i] = false
        }
    }

    private fun reserve(range: PhysicalRange) {
        val start = maxOf(range.start, base)
        val end = minOf(range.endExclusive, Platform_QEMU.RAM_END)
        if (start >= end) return

        val first = pageIndex(alignDown(start, PAGE_SIZE.toULong()))
        val lastExclusive = pageIndex(alignUp(end, PAGE_SIZE.toULong()))
        for (i in first until lastExclusive) {
            if (i in pages.indices) pages[i] = true
        }
    }

    private fun pageIndex(address: ULong): Int =
        ((address - base) / PAGE_SIZE.toULong()).toInt()

    private fun ensureInitialized() {
        if (!initialized) initialize(null)
    }

    private fun alignUp(value: ULong, alignment: ULong): ULong =
        (value + alignment - 1UL) and (alignment - 1UL).inv()

    private fun alignDown(value: ULong, alignment: ULong): ULong =
        value and (alignment - 1UL).inv()
}
