package memory

data class PhysicalRange(val start: ULong, val size: ULong) {
    val endExclusive: ULong get() = start + size
}

data class PhysicalPage(val address: ULong)