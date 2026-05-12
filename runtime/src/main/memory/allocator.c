#include <stddef.h>
#include <stdint.h>

#define HDR_SIZE  (sizeof(heap_block_t))

typedef struct heap_block {
    uint64_t size;
    uint64_t free;
} heap_block_t;

extern uint8_t __heap_start[];
extern uint8_t __heap_end[];

static uint8_t *heap_base = 0;
static uint8_t *heap_limit = 0;
static uint8_t *heap_bump = 0;
int      heap_ready = 0;

static inline size_t align16(size_t n) {
    return (n + 15u) & ~(size_t)15u;
}

void heap_init(void) {
    heap_base = __heap_start;
    heap_limit = __heap_end;
    heap_bump  = heap_base;
    heap_ready = 1;
}

extern void uart_puts_raw(const char *s);

void *malloc(size_t n) {
    if (!heap_ready || n == 0) return (void *)0;

    size_t aligned = align16(n);
    uint8_t *p = heap_base;
    
    while (p + HDR_SIZE <= heap_bump) {
        heap_block_t *hdr = (heap_block_t *)p;
        if (hdr->free && hdr->size >= aligned) {
            if (hdr->size >= aligned + HDR_SIZE + 16u) {
                uint8_t *new_p = p + HDR_SIZE + aligned;
                heap_block_t *new_hdr = (heap_block_t *)new_p;
                new_hdr->size = hdr->size - aligned - HDR_SIZE;
                new_hdr->free = 1;
                hdr->size = aligned;
            }
            hdr->free = 0;
            return (void *)(p + HDR_SIZE);
        }
        p += HDR_SIZE + hdr->size;
    }

    if (heap_bump + HDR_SIZE + aligned > heap_limit) {
        uart_puts_raw("MALLOC: heap exhausted\r\n");
        for (;;) __asm__ volatile("wfe");
    }

    heap_block_t *hdr = (heap_block_t *)heap_bump;
    hdr->size = aligned;
    hdr->free = 0;
    heap_bump += HDR_SIZE + aligned;
    return (void *)((uint8_t *)hdr + HDR_SIZE);
}

void free(void *ptr) {
    if (!ptr) return;

    heap_block_t *hdr = (heap_block_t *)((uint8_t *)ptr - HDR_SIZE);
    hdr->free = 1;

    uint8_t *next_p = (uint8_t *)hdr + HDR_SIZE + hdr->size;
    while (next_p + HDR_SIZE <= heap_bump) {
        heap_block_t *next = (heap_block_t *)next_p;
        if (!next->free) break;
        hdr->size += HDR_SIZE + next->size;
        next_p    += HDR_SIZE + next->size;
    }
}

void *calloc(size_t nmemb, size_t size) {
    size_t total = nmemb * size;
    void  *p     = malloc(total);
    if (p) {
        uint8_t *b = (uint8_t *)p;
        for (size_t i = 0; i < total; i++) b[i] = 0;
    }
    return p;
}

void *realloc(void *ptr, size_t size) {
    if (!ptr)   return malloc(size);
    if (!size)  { free(ptr); return (void *)0; }

    heap_block_t *hdr = (heap_block_t *)((uint8_t *)ptr - HDR_SIZE);
    size_t old_size   = hdr->size;
    size_t aligned    = align16(size);

    if (aligned <= old_size) return ptr;

    void *newp = malloc(size);
    if (!newp) return (void *)0;

    uint8_t *src = (uint8_t *)ptr;
    uint8_t *dst = (uint8_t *)newp;
    for (size_t i = 0; i < old_size; i++) dst[i] = src[i];
    free(ptr);
    return newp;
}

int posix_memalign(void **memptr, size_t alignment, size_t size) { 
    if (!heap_ready || size == 0) return 22;
    if (alignment < 16) alignment = 16;
    size_t aligned_size = align16(size);

    size_t offset = (size_t)(heap_bump + HDR_SIZE) % alignment;
    size_t shift = (offset == 0) ? 0 : alignment - offset;
    
    if (heap_bump + shift + HDR_SIZE + aligned_size > heap_limit) {
        return 12;
    }
    
    if (shift > 0) {
        if (shift >= HDR_SIZE + 16u) {
            heap_block_t *dummy = (heap_block_t *)heap_bump;
            dummy->size = shift - HDR_SIZE;
            dummy->free = 1;
        }
        heap_bump += shift;
    }
    
    heap_block_t *hdr = (heap_block_t *)heap_bump;
    hdr->size = aligned_size;
    hdr->free = 0;
    heap_bump += HDR_SIZE + aligned_size;
    
    *memptr = (void *)((uint8_t *)hdr + HDR_SIZE);
    return 0; 
}

void *mmap(void *addr, size_t length, int prot, int flags, int fd, long offset) {
    (void)addr; (void)prot; (void)flags; (void)fd; (void)offset;
    void *p = (void*)0;
    if (posix_memalign(&p, 4096, length) != 0) {
        return (void *)-1;
    }
    return p;
}

int munmap(void *addr, size_t length) {
    (void)length;
    free(addr);
    return 0;
}

int mprotect(void *addr, size_t len, int prot) {
    (void)addr; (void)len; (void)prot;
    return 0;
}
