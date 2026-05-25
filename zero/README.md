# Zero

Zero is the project build tool for Spezi packages.

Build a KurtOS userspace application from a directory containing `zero.toml`:

```bash
zero build
```

Example manifest:

```toml
[package]
name = "rm"
version = "0.1.0"
source = "main.spz"

[dependencies]
libkurt = { git = "https://github.com/Kurt-OS/libkurt.git", tag = "v0.1.0-dev" }

[targets]
default = ["kurt"]
```

The raw Spezi compiler still works directly. Zero is the preferred project-level entry point because it owns dependency and target resolution.

During local KurtOS development, override a git dependency with a local checkout:

```bash
ZERO_GIT_OVERRIDE_LIBKURT=/path/to/libkurt zero build
```
