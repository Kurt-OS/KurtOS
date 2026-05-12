QEMU       = qemu-system-aarch64
QEMU_FLAGS = -M virt \
             -cpu cortex-a53 \
             -m 128M \
             -nographic \
             -serial mon:stdio \
             -kernel build/kurtos.elf

QEMU_GFX_FLAGS = -M virt \
                 -cpu cortex-a53 \
                 -m 128M \
                 -display gtk \
                 -serial mon:stdio \
                 -global virtio-mmio.force-legacy=false \
                 -device virtio-gpu-device \
                 -kernel build/kurtos.elf

.PHONY: all run run-gfx debug clean

all:
	./gradlew buildImage

run: all
	$(QEMU) $(QEMU_FLAGS)

run-gfx: all
	$(QEMU) $(QEMU_GFX_FLAGS)

debug: all
	$(QEMU) $(QEMU_FLAGS) -s -S

clean:
	./gradlew clean
