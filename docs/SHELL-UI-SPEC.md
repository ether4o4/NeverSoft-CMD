# NeverSoft Shell UI Spec

This visual spec is intentionally separate from the native package-port work. The terminal engine stays native; this defines how the shell surface should look once the base is operational.

## Layout

- Terminal content fills the entire usable Android app area edge-to-edge.
- No floating card, inset panel, outer margin, or framed window inside the app.
- Respect Android system insets only where required for status/navigation bars.
- Terminal background reaches every other visible corner of the usable screen.
- Dark near-black terminal surface with a very subtle charcoal gradient/vignette, matching the approved reference image.

## Top bar

- Thin dark title bar integrated with the terminal surface.
- Left side: small terminal-style icon.
- Display title/path should use NeverSoft branding, e.g. `NeverSoft_Shell` or `NeverSoft_Shell/cmd`.
- The title is visual chrome only; it must not change or fake the underlying Linux filesystem or native shell behavior.
- Keep controls minimal and visually similar to the approved desktop command-prompt reference without adding extra panels.

## Terminal

- Monospace light text on near-black background.
- Minimal initial prompt; no splash card or dashboard.
- Native shell remains the real NeverSoft/Termux-derived shell environment.
- Terminal view must resize correctly with the Android keyboard and orientation changes without introducing permanent margins.

## Scope rule

Do not spend package-porting time on UI polish until the native bootstrap, APK, APT repository, and initial package waves are operational. This document locks the appearance target so it can be implemented later without ambiguity.
