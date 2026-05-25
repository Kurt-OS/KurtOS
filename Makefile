QEMU       = qemu-system-aarch64
GRADLE_FLAGS =
ifneq ($(KURTOS_ROOT),)
GRADLE_FLAGS += -Pkurtos.root=$(KURTOS_ROOT)
endif
QEMU_FLAGS = -M virt \
             -cpu cortex-a53 \
             -m 128M \
             -nographic \
             -serial stdio \
             -monitor none \
             -global virtio-mmio.force-legacy=false \
             -drive if=none,file=build/flx.img,format=raw,id=flx0 \
             -device virtio-blk-device,drive=flx0 \
             -kernel build/kurtos.elf

QEMU_GFX_FLAGS = -M virt \
                 -cpu cortex-a53 \
                 -m 128M \
                 -display gtk \
                 -serial stdio \
                 -monitor none \
                 -global virtio-mmio.force-legacy=false \
                 -device virtio-gpu-device \
                 -device virtio-keyboard-device \
                 -device virtio-tablet-device \
                 -drive if=none,file=build/flx.img,format=raw,id=flx0 \
                 -device virtio-blk-device,drive=flx0 \
                 -kernel build/kurtos.elf

.PHONY: all run run-gfx debug clean

all:
	./gradlew buildImage $(GRADLE_FLAGS)

run: all
	$(QEMU) $(QEMU_FLAGS)

run-gfx: all
	$(QEMU) $(QEMU_GFX_FLAGS)

debug: all
	$(QEMU) $(QEMU_FLAGS) -s -S

clean:
	./gradlew clean
