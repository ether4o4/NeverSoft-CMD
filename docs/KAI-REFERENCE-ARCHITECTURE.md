# Kai reference architecture notes for NeverSoft

Reference inspected: Kai 2.9.0 Android APK and source commit `14b347f50056d27fbf39c16d33b7dbbf189e5cdf` from `SimonSchubert/Kai`.

This document is design-only. It must not change the current Phase 1 priority: build and verify a native NeverSoft Termux-compatible environment under `com.neversoft.shell` with no proot dependency.

## What Kai does well

Kai provides a polished agent layer around a small Android Linux sandbox. Its Android sandbox is Alpine Linux under proot, with a downloaded rootfs, persistent per-conversation shell sessions, background process management, a terminal/files UI, shell-tool execution, MCP integration, provider adapters, memory tools, scheduling/heartbeat features, and on-device inference.

Useful concepts to adapt later:

1. **Per-conversation persistent shell sessions**
   - Keep cwd, exports and shell state between tool calls.
   - Separate interactive/user terminal sessions from AI sessions.
   - Persist transcript separately from process lifetime.

2. **Unified tool schema + executor**
   - Each tool exposes a machine-readable schema.
   - Provider-specific tool-call formats are normalized into one internal execution layer.
   - Add iteration limits, repeated-call detection, timeouts and result truncation.

3. **MCP as an extension bus**
   - Store MCP server configs.
   - Discover tools dynamically.
   - Enable/disable servers and tools independently.
   - Wrap discovered MCP tools in the same internal tool interface as built-in tools.

4. **Provider abstraction**
   - Treat OpenAI, Anthropic, Gemini and OpenAI-compatible endpoints as adapters around a common chat/tool model.
   - Keep provider-specific reasoning/tool-call serialization in the adapter layer rather than the UI.

5. **Model import UX**
   - Kai supports direct local-model import, but its Android import path is tied to `.litertlm` files.
   - NeverSoft should generalize this into runtime adapters instead of hard-coding one model format.

6. **Shell + files + packages as first-class UI**
   - Kai exposes Terminal, Files and package-management concepts alongside chat.
   - NeverSoft should keep the terminal as the primary environment and later add AI as a peer surface, not as the owner of the shell.

## Where NeverSoft should deliberately differ

Kai's Android Linux environment is proot + Alpine. NeverSoft is intentionally building a native Termux-compatible prefix instead:

- package id: `com.neversoft.shell`
- native prefix: `/data/data/com.neversoft.shell/files/usr`
- no proot dependency for the core shell
- own package repository built for the NeverSoft prefix
- full Termux-style package ecosystem

This should give NeverSoft a stronger foundation for compilers, native packages, long-running local services and local model runtimes.

## Future NeverSoft AI/runtime architecture

After the native terminal/package ecosystem is proven, add a **Model Runtime Registry** rather than a single local-model backend.

Suggested adapters:

- `llama.cpp` / GGUF (primary local adapter)
- LiteRT / `.litertlm`
- OpenAI-compatible local endpoints
- Ollama-compatible endpoints where available
- optional future MLC or other Android-capable runtimes
- cloud provider adapters (OpenAI, Anthropic, Gemini, Groq, OpenRouter, etc.)

A model entry should describe:

- runtime adapter
- model path or endpoint
- model id/display name
- context size
- tool-calling capability
- multimodal capability
- preferred prompt/chat template
- memory/RAM estimate
- launch arguments
- health-check command

The UI can then offer **Add Model** without pretending every model file is directly runnable by every backend. The runtime registry decides which installed engine can launch it.

## Future tool architecture

NeverSoft should eventually expose at least Kai-equivalent categories:

- shell execution
- background process management
- file read/write/open
- web fetch/search
- notifications
- alarms/timers
- calendar
- memory store/forget/reinforce
- scheduling/heartbeat
- MCP servers/tools
- SSH/remote-host helpers

Then go beyond Kai with native-Termux-derived capabilities:

- package install/update/remove tools using NeverSoft APT
- compiler/build tools
- Git/GitHub workflows
- local HTTP/service management
- model-runtime lifecycle tools
- model download/quantization/conversion helpers
- script library and reusable command templates
- explicit permission scopes for tools
- per-project workspaces
- terminal session handoff between user and agent

## UI target

The shell remains edge-to-edge across the usable Android screen. No floating terminal card. The NeverSoft visual shell can use the requested dark CMD-like chrome, but the backend is the real NeverSoft native Linux environment.

Later AI/chat can be layered as a split or switchable peer view while preserving the full terminal surface and session state.

## Rule for current development

Do not add any of the AI/runtime code above until Phase 1 native bootstrap + app shell + NeverSoft package manager are operational and smoke-tested on device. These notes exist so the finished-product direction is not lost while the core is being built.
