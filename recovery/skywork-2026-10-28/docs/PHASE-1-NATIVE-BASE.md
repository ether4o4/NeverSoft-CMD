# Phase 1 — Operational NeverSoft shell + local Hugging Face AI

## Product gate

NeverSoft is not required to be a byte-for-byte Termux fork. The goal is one Android APK with:

- a real interactive local shell and PTY;
- a practical Termux-class package/tool environment;
- Android shared-storage access;
- GitHub repo download/install capability;
- Hugging Face GGUF models running locally;
- AI tool execution against the same NeverSoft HOME/PREFIX/files/processes as the visible terminal;
- top AI / bottom terminal split screen with a draggable divider.

## Runtime strategy

To get an operational environment quickly, NeverSoft ships a pinned official Termux bootstrap instead of rebuilding the entire Termux package graph in CI.

The physical environment remains NeverSoft-owned:

- application ID: `com.neversoft.shell`
- data directory: `/data/data/com.neversoft.shell`
- home: `/data/data/com.neversoft.shell/files/home`
- prefix: `/data/data/com.neversoft.shell/files/usr`

Some upstream Termux binaries/packages still contain `/data/data/com.termux/files/usr` as a compiled-in absolute path. NeverSoft uses a small `proot` compatibility alias so that historical path resolves to the same physical NeverSoft prefix. It is not a second Alpine/Debian rootfs and does not create a separate AI sandbox.

The visible terminal and AI command runner therefore share the same files, installed packages, projects and processes.

## Build

`./scripts/phase1-native-base.sh`

1. downloads the pinned ARM64 Termux bootstrap;
2. injects NeverSoft helpers;
3. prepares the NeverSoft-owned Android app module while reusing Termux terminal-view/emulator libraries;
4. embeds the bootstrap;
5. builds an ARM64 APK.

This intentionally removes the previous multi-hour source rebuild from the normal APK loop.

## First-launch shell setup

The app extracts the bootstrap into NeverSoft's prefix, relocates symlinks and apt/dpkg state, then attempts to install the small path-compatibility layer. If the device is offline, the base shell still opens and compatibility setup can be retried later.

Useful commands include:

- `pkg install git`
- `pkg install python`
- `pkg install nodejs`
- `pkg install openssh`
- `ghget owner/repo`
- `storage-setup`

## Hugging Face local AI

The top pane is local-first and Hugging Face specific:

1. paste a `huggingface.co`/`hf.co` GGUF download URL;
2. optionally provide an HF token for private/gated repos;
3. NeverSoft downloads the model under its private `files/models/` directory;
4. NeverSoft installs the Termux `llama-cpp` package into the same shell environment;
5. `llama-server` starts on `127.0.0.1:8080`;
6. the top chat uses its OpenAI-compatible `/v1/chat/completions` endpoint;
7. model `run` blocks are executed by `ShellRuntime` in the same HOME/PREFIX as the terminal;
8. caution/destructive AI commands require user approval.

No external cloud AI provider is required for this path.

## Operational smoke test

A build is useful when the installed APK can demonstrate:

```sh
pwd
ls
mkdir -p ~/test
echo hello > ~/test/file.txt
cat ~/test/file.txt
storage-setup
cd ~/storage/downloads
pkg update
pkg install git curl

ghget ether4o4/shell-ai-scripts ~/shell-ai-scripts
```

Then load a Hugging Face GGUF in the top pane and ask the model to inspect/create/edit a file. The resulting changes must be visible immediately from the bottom terminal.

## Next after this gate

Only after the operational smoke test passes: improve model presets, persistent chats, process management, MCP/tools, GitHub credentials, package UX, signing, and broader polish.
