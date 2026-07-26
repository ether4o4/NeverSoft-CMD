# NeverSoft Shell UI Spec

This visual spec defines the NeverSoft workspace while the native shell remains the underlying execution environment.

## Visual direction

- Default interface is **Windows 11-inspired**, not a Termux replica and not an Expo/mobile-dashboard look.
- Use restrained dark surfaces, subtle depth, soft corners where controls/panels need separation, and a cool Windows-style blue accent.
- Favor clean sans-serif UI chrome with Segoe-like spacing/proportions; terminal content remains monospace.
- Glass/acrylic-style treatment may be used for AI/settings/tool surfaces, but never reduce terminal readability.
- Avoid excessive gradients, glowing borders, gamer styling, oversized cards, or floating phone-widget aesthetics.
- The terminal itself stays close to a modern Windows Terminal / command-prompt feel: near-black, high contrast, minimal decoration.

## Layout

- Terminal content fills the entire usable Android app area edge-to-edge when used alone.
- No floating card, inset panel, outer margin, or framed window around the terminal.
- Respect Android system insets only where required for status/navigation bars.
- Terminal background reaches every other visible corner of the usable screen.

## AI + terminal split

- Split-screen orientation is **vertical in page flow: AI on TOP, terminal on BOTTOM**.
- Never use a left/right split as the primary phone layout.
- A horizontal Windows-style divider/rail separates the panes and is draggable so the user can resize either pane.
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

## App chrome

- Keep the title/status chrome thin and integrated with the workspace.
- Display NeverSoft branding such as `NeverSoft_Shell` without pretending Android has a Windows filesystem.
- Controls should resemble modern Windows 11 spacing, icon sizing, hover/pressed hierarchy, and muted dark surfaces.
- Primary accent target: cool blue similar to Windows 11 system accent; current native theme uses `#60CDFF`.
- Base UI surface target: `#202020`; terminal surface target: `#0C0C0C`.

## Terminal

- Monospace light text on near-black background.
- Minimal initial prompt; no splash card or fake dashboard in the terminal itself.
- Native shell is the real NeverSoft environment.
- Terminal view must resize correctly with the Android keyboard, split divider, and orientation changes without introducing permanent margins.

## AI / settings surfaces

- AI chat, model manager, scripts, notes, tools, and settings may use rounded Windows 11-style surfaces because they are application UI, not terminal content.
- Keep information density high enough for a power-user tool; do not turn every setting into a giant mobile card.
- Provider/model state should be visible but compact.
- Destructive AI shell actions should use a native approval surface consistent with the Windows-inspired visual language.

## Current Phase 1 behavior

- AI pane and horizontal divider remain hidden.
- Terminal occupies the entire usable application area after bootstrap setup.
- The Android source already uses a top-AI / bottom-terminal container hierarchy so the later split-screen layer does not require replacing the terminal UI.
- Phase 1 must not be delayed for cosmetic polish; this spec locks the final direction while shell/bootstrap work continues.
