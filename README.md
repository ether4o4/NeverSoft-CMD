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

## Phase 1 build

Prerequisites: Linux host, Docker, git, Python 3, JDK/Android requirements used by upstream Termux, and enough disk space for package builds.

Build the first ARM64 native bootstrap and APK:

```bash
./scripts/phase1-native-base.sh
```

That pipeline:

1. clones upstream `termux-packages`;
2. changes the build identity to NeverSoft;
3. rebuilds the bootstrap for `/data/data/com.neversoft.shell/files/usr`;
4. clones Termux app `v0.118.3`;
5. changes Android identity/constants/resources to NeverSoft;
6. embeds only the custom NeverSoft bootstrap;
7. builds an ARM64 debug APK.

Artifacts are written under `artifacts/bootstrap/` and `artifacts/apk/`.

## Package expansion

Curated waves:

```bash
./scripts/build-package-wave.sh 1 aarch64
./scripts/build-package-wave.sh 2 aarch64
```

Resumable full main-repository pass:

```bash
./scripts/build-repository.sh main aarch64
```

Successful packages and failures are recorded under `artifacts/build-state/`, so repeated runs skip completed packages and keep a reproducible failure queue.

Generate the static NeverSoft APT repository from built `.deb` files:

```bash
./scripts/make-apt-repo.sh aarch64
```

The generated tree is placed in `artifacts/publish/apt/termux-main/`. `scripts/publish-apt-repo.sh` publishes it to the `packages` branch. NeverSoft's rebuilt `apt` points only at that branch and never at the stock Termux repositories.

## Gate before expansion

The first milestone is not visual customization. It is:

> Install stock Termux and NeverSoft side-by-side, launch a native NeverSoft bash session, run `pkg update`, and install/remove a NeverSoft-built package without any executable or library depending on `/data/data/com.termux/files/usr`.

See `docs/PHASE-1-NATIVE-BASE.md` for the detailed gates.
