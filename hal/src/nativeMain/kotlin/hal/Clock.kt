package hal

import kotlinx.cinterop.ExperimentalForeignApi
import mmio.arm_cntfrq_el0
import mmio.arm_cntpct_el0

@OptIn(ExperimentalForeignApi::class)
object Clock {
    private val frequency: ULong get() = arm_cntfrq_el0()

    fun uptimeMillis(): ULong {
        val freq = frequency
        if (freq == 0UL) return 0UL
        return (arm_cntpct_el0() * 1000UL) / freq
    }
}
