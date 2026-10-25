# NeverSoft CMD

> **Next-generation foundation audit (2026-10-24):** The existing APK uses a
> stock-bootstrap compatibility path, not a verified independent package
> distribution. Milestone 1 is not accepted. See [architecture](ARCHITECTURE.md)
> and the [milestone ledger](PLAN.md). Candidate CI now preserves SHA-versioned
> test artifacts and does not overwrite the existing release.

[![Download APK](https://img.shields.io/badge/download-neversoft--cmd.apk-3DDC84?logo=android&logoColor=white&style=for-the-badge)](https://github.com/ether4o4/NeverSoft-CMD/releases/latest/download/neversoft-cmd.apk)
[![Build NeverSoft Native APK](https://img.shields.io/github/actions/workflow/status/ether4o4/NeverSoft-CMD/build-native.yml?branch=main&label=build&style=flat-square)](https://github.com/ether4o4/NeverSoft-CMD/actions/workflows/build-native.yml)
[![Release](https://img.shields.io/github/release-date/ether4o4/NeverSoft-CMD?label=last%20build&style=flat-square)](https://github.com/ether4o4/NeverSoft-CMD/releases/tag/android-latest)

## Download

Existing legacy ARM64 (`arm64-v8a`) debug build (not next-generation acceptance):

**<https://github.com/ether4o4/NeverSoft-CMD/releases/latest/download/neversoft-cmd.apk>**

```bash
curl -fL -o neversoft-cmd.apk \
  https://github.com/ether4o4/NeverSoft-CMD/releases/latest/download/neversoft-cmd.apk
```

That URL points to the preserved `android-latest` release. New candidate builds
are SHA-versioned Actions artifacts, not automatic replacements. A matching
`neversoft-cmd.apk.sha256` is published beside the existing release:

```bash
curl -fL -O https://github.com/ether4o4/NeverSoft-CMD/releases/latest/download/neversoft-cmd.apk.sha256
sha256sum -c <(sed 's#release/##' neversoft-cmd.apk.sha256)
```

Notes:

- Application ID `com.neversoft.shell`, installs alongside stock Termux.
- ARM64 devices only; the build is debug-signed, so allow "install from unknown sources".
- A differently signed APK cannot update an existing installation. Do not
  uninstall and lose its private data; retain the original signing key or use a
  separate test device.

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

Reproduce the legacy compatibility baseline APK:

```bash
./scripts/phase1-native-base.sh
```

That pipeline:

1. downloads a pinned stock Termux bootstrap and adds helper scripts;
2. clones Termux app `v0.118.3`;
3. builds the NeverSoft-owned application with upstream terminal libraries;
4. embeds the compatibility bootstrap and builds an ARM64 debug APK.

The independent source-built candidate uses a separate gate:

```bash
bash scripts/build-native-bootstrap.sh aarch64
```

It rebuilds packages for NeverSoft's prefix and rejects foreign-prefix archives.
It requires Docker. Do not stage it into the legacy APK yet: native installer
integration, signing and on-device package transactions remain outstanding.

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

The generated tree is placed in `artifacts/publish/apt/termux-main/`.
`scripts/publish-apt-repo.sh` targets the `packages` branch, while the current
build configuration points at `main/apt/termux-main`, which was absent at audit.
This mismatch and signed metadata/key distribution must be resolved before
publishing a usable repository. The native candidate's source configuration
must never be confused with the stock repositories in the legacy APK.

## Gate before expansion

The first milestone is not visual customization. It is:

> Install stock Termux and NeverSoft side-by-side, launch a native NeverSoft bash session, run `pkg update`, and install/remove a NeverSoft-built package without any executable or library depending on `/data/data/com.termux/files/usr`.

See `docs/PHASE-1-NATIVE-BASE.md` for the detailed gates.
