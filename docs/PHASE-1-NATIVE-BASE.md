# Phase 1 — Native NeverSoft base

## Scope

Phase 1 is only the Termux remake. No AI, dashboard, model runtime, or assistant integration belongs in this phase.

## Identity

- Android application ID: `com.neversoft.shell`
- App data directory: `/data/data/com.neversoft.shell`
- Rootfs: `/data/data/com.neversoft.shell/files`
- Home: `/data/data/com.neversoft.shell/files/home`
- Prefix: `/data/data/com.neversoft.shell/files/usr`
- Native execution only; no proot compatibility layer

## Gate 1 — Bootstrap

Build an aarch64 bootstrap from source for the NeverSoft prefix using the upstream `termux-packages` build system. The bootstrap must include the same core package class as a normal Termux bootstrap: apt, bash, coreutils, termux-core, termux-exec, termux-tools, dpkg support and required runtime libraries.

Success means every executable and library in the bootstrap resolves the NeverSoft prefix and no executable depends on `/data/data/com.termux/files/usr`.

## Gate 2 — App shell

Fork the Termux 0.118.3 Android app and change the Android application ID, app constants, bootstrap source and branding needed to launch the custom bootstrap. Keep internal Java package names unless changing them is technically necessary; Android identity is the application ID, not the Java source namespace.

Success means stock Termux and NeverSoft can be installed at the same time and NeverSoft opens a working native bash session.

## Gate 3 — Package repository

NeverSoft packages cannot be mixed with stock Termux packages because they are compiled for different absolute prefixes. Build and publish a NeverSoft APT repository, then make `pkg` and `apt` use only that repository.

Success means `pkg update`, `pkg upgrade`, install, remove and dependency resolution work without contacting a stock Termux package repository.

## Gate 4 — Package waves

Packages are expanded in dependency-aware waves. Each wave must build, install and execute before the next begins.

1. Bootstrap/core
2. Development base: git, openssh, curl, wget, python, nodejs, clang, cmake, make, pkg-config
3. Common CLI: ripgrep, jq, tmux, htop, ffmpeg, rsync, zip/unzip, tar utilities
4. Languages/toolchains: rust, golang, ruby, php and associated build dependencies
5. Extended main repository
6. X11 packages
7. Root/optional packages where applicable

Failures go into a reproducible build-failure queue. Do not hand-edit thousands of packages preemptively.

## Accuracy rule

A package is considered ported only when:

- it builds against the NeverSoft prefix;
- its package metadata contains the correct prefix;
- dynamic linker/interpreter paths are correct;
- it installs through NeverSoft APT;
- a basic runtime smoke test succeeds;
- scanning finds no executable/runtime dependency on `/data/data/com.termux`.
