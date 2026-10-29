# Next-generation architecture frame

Audit baseline: `ec3e0d5f9ecc38cef8e56c93d6d15faf11830c6d`, 2026-10-24.

- **Ownership:** `com.neversoft.shell` remains the application. Reuse the upstream
  terminal-emulator, terminal-view and PTY JNI libraries; do not replace them with
  a simulated shell. Sessions, processes, projects and operations are distinct entities.
- **Residency:** private NeverSoft HOME/PREFIX remain authoritative. Workspace
  state is local-first. Future SAF import/export must be explicit.
- **Execution/AI:** one execution environment shared by terminal and authorized
  agents. Optional local models; cloud transmission requires authorization.
  Deterministic operations do not require a model.
- **Navigation:** future Terminal and Command Center modes must attach to the
  same service-owned session registry. Activity-owned sessions are insufficient.
- **Visual system:** native adaptive dp layouts; graphite `#0B0D11`, surfaces
  `#151A22`/`#202732`, cyan `#38C9DC`, violet `#8576C8`, soft-white `#E8EDF3`.
  UI work is gated on native package installation/removal on the target device.

## Ground truth, not advertised capability

The baseline's `build-bootstrap.sh` downloads a pinned **stock Termux** archive.
`RuntimeRepair` rewrites scripts, but does not rebuild ELF binaries.
Its `pkg install` downloads and extracts `.deb` payloads without maintaining
dpkg installation state or running maintainer scripts. Removal is unsupported.
The configured custom APT URL on `main` returned HTTP 404 during this audit.
Therefore neither independent package management nor Milestone 1 is accepted.

The current Activity creates one real `TerminalSession`. There is no
service-owned terminal registry. The manifest has no foreground terminal
service; process survival and restoration are not established.

The app inherits compile SDK 30 and **target SDK 28** from upstream.
Running on Android 16 is not the same as targeting API 36. Do not raise the
target blindly: execute-from-private-data restrictions affect terminal apps.
A target-SDK migration requires a tested executable-loading architecture.

## Build and trust separation

1. **Compatibility baseline:** existing stock-bootstrap build, retained for
   regression work and explicitly named as such.
2. **Native-prefix candidate:** source-built via `build-native-bootstrap.sh`,
   pinned package inputs, no stock-archive fallback, archive and package gates.
   Outputs remain in `artifacts/native-bootstrap/`, separate from the legacy
   `artifacts/bootstrap/` input consumed by APK staging.
3. **Device acceptance:** clean-install execution, package transaction and
   restoration evidence. A ZIP scan cannot satisfy this step.
4. **Release:** approved signing identity, signed package repository and passed
   device matrix. CI builds alone must not update `android-latest`.

The native-prefix workflow currently builds candidate distribution artifacts,
not an accepted APK. Native installation and legacy-data migration remain gated.
The legacy installer must not be pointed at a new native bootstrap until it stops
overwriting the package manager with the compatibility overlay helper.

## Reference repository audit

Repository roots, metadata and README architecture were inspected, not every
implementation. No code was copied and no integration compatibility is claimed.

| Repository | Candidate reuse | Constraint |
| --- | --- | --- |
| Open-Mine | retained process/service and panel state patterns | native dependency and license review before extraction |
| NeverSoft-Mobile-Desktop | model adapter and permission UX concepts | React Native layer is not a drop-in Java module |
| Autos | authorized automation setup and failure records | README marks ARTEMIS/NullClaw end-to-end work unverified |
| Intelli-Shell | terminal integration patterns | Termux license/identity/prefix review required |
| NeverSoft-Services-OS | provider/MCP architecture | Apache-2.0 root; inspect component licenses and dependency graph |
| Retro-Space | explicit SAF import/export patterns | no unrestricted Android virtualization claim |

No clear root license was returned for Open-Mine, NeverSoft-Mobile-Desktop,
Autos or Retro-Space. Resolve attribution/licensing before copying code.
