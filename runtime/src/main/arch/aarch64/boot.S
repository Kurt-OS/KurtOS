.section ".text.boot", "ax"
.global _start
.type   _start, %function

_start:
    mov     x19, x0

    mrs     x0, mpidr_el1
    and     x0, x0, #0xFF
    cbz     x0, primary_cpu

secondary_spin:
    wfe
    b       secondary_spin

primary_cpu:
    ldr     x0, =__stack_top
    mov     sp, x0

    ldr     x0, =0x407F0000
    msr     tpidr_el0, x0

    ldr     x1, =__bss_start
    ldr     x2, =__bss_end

bss_zero_loop:
    cmp     x1, x2
    b.ge    bss_done
    str     xzr, [x1], #8
    b       bss_zero_loop

bss_done:
    mrs     x0, cpacr_el1
    orr     x0, x0, #(3 << 20)
    msr     cpacr_el1, x0
    isb

    mov     x0, x19
    bl      runtime_init

halt_loop:
    wfe
    b       halt_loop

.size _start, . - _start
