# Verified milestone ledger

No milestone is accepted yet. Never infer runtime acceptance from this ledger.

## Milestone 1 — in progress

- [x] Audit exact repository and native terminal path.
- [x] Preserve package identity and existing PTY implementation.
- [x] Fix archive listing/pipefail false negative.
- [x] Implement archive inspection without extraction.
- [x] Check ELF64/AArch64, payload prefix, maintainer scripts and symlinks.
- [x] Reject empty package validation.
- [x] Correct APT Release checksum paths to be relative to the suite.
- [x] Restore a separate source-built native bootstrap entrypoint.
- [x] Remove rolling-release writes from candidate CI.
- [ ] Compile and inspect baseline APK in this run.
- [ ] Complete native-prefix bootstrap/package cross-build.
- [ ] Resolve package signing/keyring and reachable versioned repository.
- [ ] Implement transaction-safe native installation and non-destructive
      migration from the old overlay distribution.
- [ ] Replace Activity ownership with foreground-service session ownership.
- [ ] On a clean S24 Ultra: shell, `pkg update`, new native package
      install/execute/remove, dpkg state and process-exit evidence.

## Subsequent milestones — blocked by the foundation acceptance gate

2. Dual UI: persistent session registry first; modes share process identities.
3. Development tools: SAF, bounded editor, Git tasks and errors.
4. PRoot environments: authenticated rootfs inputs, backups and lifecycle.
5. Models/VIBE: GGUF validation, llama.cpp, permissioned operation journal.
6. Agents: explicit adapters and per-framework device tests.
7. Automation: separately granted Android/API/accessibility/ADB capabilities.
8. Reliability/release: real device matrix, signatures, performance and rollback.

## Current constraints

- ADB returned an empty device list on 2026-10-24. No S24 acceptance was performed.
- Local Docker is absent. The native-prefix GitHub job uses an isolated Linux
  runner and preserves logs/artifacts without publishing a release.
- APK signing-key continuity is not established. Never recommend uninstalling
  an existing installation to work around a signature mismatch; protect its data.
- Production package signing remains unresolved; the old package-tree adapter
  uses `trusted=yes` and must not be presented as production-ready.

## Evidence standard

Each accepted capability needs UI action → real operation → result/exit status →
saved state → relaunch check → logs → failure recovery. Save device model, Android
version, APK SHA256 and source SHA with the test results. Record “not tested”
when evidence is absent.
