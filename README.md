# NeverSoft CMD

NeverSoft CMD is a personal, independent Termux-compatible Android terminal distribution.

## Current objective

Build a native Termux-style userland that installs beside stock Termux under its own Android package identity and filesystem prefix.

- Application ID: `com.neversoft.shell`
- Data dir: `/data/data/com.neversoft.shell`
- Rootfs: `/data/data/com.neversoft.shell/files`
- Home: `/data/data/com.neversoft.shell/files/home`
- Prefix: `/data/data/com.neversoft.shell/files/usr`
- No `proot`
- No dependency on an installed `com.termux`
- No AI/dashboard work until the base terminal and package ecosystem are operational

The first milestone is a native bootstrap that launches `bash` and supports `apt/pkg`, `coreutils`, and the libraries required for a normal Termux-style environment. Package compatibility is then expanded systematically using a build-failure queue.
