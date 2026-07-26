# NeverSoft Shell UI Spec

This visual spec defines the NeverSoft workspace while the native shell remains the underlying execution environment.

## Layout

- Terminal content fills the entire usable Android app area edge-to-edge when used alone.
- No floating card, inset panel, outer margin, or framed window inside the app.
- Respect Android system insets only where required for status/navigation bars.
- Terminal background reaches every other visible corner of the usable screen.
- Dark near-black terminal surface with minimal chrome.

## AI + terminal split

- Split-screen orientation is **vertical in page flow: AI on TOP, terminal on BOTTOM**.
- Never use a left/right split as the primary phone layout.
- A horizontal divider separates the panes and is draggable so the user can resize either pane.
- Either pane can expand to full usable screen size.
- Terminal-only mode removes the AI pane and divider completely so the terminal becomes edge-to-edge.
- The AI and terminal panes must share the same NeverSoft filesystem/session environment when AI integration is enabled.

```text
┌─────────────────────────────┐
│          AI CHAT            │
│                             │
├─────────────────────────────┤
│          TERMINAL           │
│                             │
└─────────────────────────────┘
```

## Top bar

- Thin dark title bar integrated with the workspace rather than a floating window frame.
- Display title/path should use NeverSoft branding, e.g. `NeverSoft_Shell`.
- The title is visual chrome only; it must not change or fake the underlying Linux filesystem or native shell behavior.
- Keep controls minimal.

## Terminal

- Monospace light text on near-black background.
- Minimal initial prompt; no splash card or dashboard.
- Native shell is the real NeverSoft environment.
- Terminal view must resize correctly with the Android keyboard, split divider, and orientation changes without introducing permanent margins.

## Current Phase 1 behavior

- AI pane and horizontal divider remain hidden.
- Terminal occupies the entire usable application area after bootstrap setup.
- The Android source already uses a top-AI / bottom-terminal container hierarchy so the later split-screen layer does not require replacing the terminal UI.
