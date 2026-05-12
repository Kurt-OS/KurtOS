#include <stddef.h>
#include <stdint.h>

extern void heap_init(void);
extern void kotlin_main(void);

extern void (*__init_array_start []) (void);
extern void (*__init_array_end []) (void);

uint64_t kurtos_boot_fdt = 0;

static uint64_t l1_table[512] __attribute__((aligned(4096)));

static void setup_mmu(void) {
    uint64_t mair = 0x0000000000004400ULL; 
    __asm__ volatile("msr mair_el1, %0" : : "r"(mair));

    l1_table[0] = 0x00000000 | (0 << 2) | (1 << 10) | 1;
    l1_table[1] = 0x40000000 | (1 << 2) | (1 << 10) | 1;

    uint64_t tcr = 25; 
    __asm__ volatile("msr tcr_el1, %0" : : "r"(tcr));
    __asm__ volatile("msr ttbr0_el1, %0" : : "r"(l1_table));

    uint64_t sctlr;
    __asm__ volatile("mrs %0, sctlr_el1" : "=r"(sctlr));
    sctlr |= 1; 
    __asm__ volatile("msr sctlr_el1, %0\n\tisb" : : "r"(sctlr));
}

__attribute__((noreturn))
void runtime_init(uint64_t fdt_base)
{
    *(volatile unsigned int *)0x09000000 = '0';
    kurtos_boot_fdt = fdt_base;

    setup_mmu();

    heap_init();
    *(volatile unsigned int *)0x09000000 = '1';

    size_t count = __init_array_end - __init_array_start;
    for (size_t i = 0; i < count; i++) {
        __init_array_start[i]();
    }
    *(volatile unsigned int *)0x09000000 = '2';

    kotlin_main();
    *(volatile unsigned int *)0x09000000 = '3';

    for (;;) {
        __asm__ volatile("wfe");
    }
}
