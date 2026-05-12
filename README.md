# KurtOS
Kotlin/Native operating system.

## Modules

- `:runtime` builds the freestanding boot, memory, minimal libc, and Kotlin/Native ABI objects.
- `:hal` owns platform constants, MMIO, UART, and memory reporting.
- `:userspace` owns built-in shell commands.
- `:shell` owns the interactive command loop.
- `:kernel` owns the Kotlin entry point and produces the static kernel library.

## Build

```sh
./gradlew buildImage
```

The image build uses the Kotlin/Native debug binary by default for faster edit-build loops.
Use `./gradlew -Pkurtos.release=true buildImage` for a release-optimized kernel library.

Run the serial shell:

```sh
make run
```

Run with the QEMU virtio-gpu device:

```sh
make run-gfx
```

Graphics shell commands:

- `virtio-scan`
- `gfx-info`
- `gfx-clear [hex-color]`
- `gfx-test`
