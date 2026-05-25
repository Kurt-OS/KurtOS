# KurtOS

KurtOS is a small Kotlin/Native hobby OS kernel and runtime.

Build the kernel image and an empty FLX filesystem:

```bash
./gradlew buildImage
```

Build with a supplied root filesystem tree:

```bash
./gradlew buildImage -Pkurtos.root=/path/to/root
```

The default distribution lives outside this repository as `kroko`. KurtOS does
not own distribution programs; it only packages the root tree it is given.
