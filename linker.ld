OUTPUT_FORMAT("elf64-littleaarch64")
OUTPUT_ARCH(aarch64)
ENTRY(_start)

SECTIONS
{
    . = 0x40000000;

    .text.boot : ALIGN(0x1000) {
        KEEP(*(.text.boot))
    }

    .text : ALIGN(0x1000) {
        *(.text .text.*)
    }

    .rodata : ALIGN(0x1000) {
        *(.rodata .rodata.*)
        *(.got .got.*)
        *(.got.plt)
    }

    .data : ALIGN(0x1000) {
        *(.data .data.*)
        . = ALIGN(8);
        __init_array_start = .;
        *(.init_array .init_array.*)
        __init_array_end = .;
        . = ALIGN(8);
        *(.fini_array .fini_array.*)
    }

    .bss : ALIGN(0x1000) {
        __bss_start = .;
        *(.bss .bss.*)
        *(COMMON)
        __bss_end = .;
    }

    . = ALIGN(0x1000);
    __tls_base = .;
    . += 64K;

    . = ALIGN(0x1000);
    __heap_start = .;
    . += 64M;
    __heap_end = .;

    __stack_top = 0x48000000;

    /DISCARD/ : {
        *(.comment)
        *(.note*)
        *(.gnu*)
        *(.ARM.exidx*)
        *(.ARM.extab*)
    }
}
