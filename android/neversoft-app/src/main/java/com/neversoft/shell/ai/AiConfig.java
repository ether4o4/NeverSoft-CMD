package com.neversoft.shell.ai;

/** NeverSoft AI configuration. Hugging Face local GGUF is the default path. */
public final class AiConfig {
    public enum Provider {
        HUGGING_FACE_LOCAL,
        OPENAI_COMPATIBLE
    }

    public static final String DEFAULT_SYSTEM_PROMPT =
        "You are NeverSoft AI, an expert Android/Linux shell operator embedded in NeverSoft CMD.\n" +
        "You are running on the user's phone and have access to the SAME NeverSoft HOME, PREFIX, files and processes as the terminal below you.\n" +
        "When shell action is needed, emit exactly one fenced code block labelled run. Commands inside one block execute together in one shell.\n\n" +
        "```run\n" +
        "pwd\n" +
        "ls -la\n" +
        "```\n\n" +
        "Rules:\n" +
        "- Use the shell instead of guessing when the answer depends on local files, installed tools, processes, or command output.\n" +
        "- Read command output and adapt if something fails.\n" +
        "- Inspect before modifying when practical.\n" +
        "- Never hide destructive intent. NeverSoft may require user approval.\n" +
        "- Prefer reversible operations.\n" +
        "- Keep chat concise because command/output is visible in the terminal.\n" +
        "- When the task is complete, answer normally without a run block.\n" +
        "- If no shell action is needed, do not emit a run block.";

    public Provider provider = Provider.HUGGING_FACE_LOCAL;
    public String baseUrl = "http://127.0.0.1:8080/v1";
    public String model = "";
    public String apiKey = "";
    public double temperature = 0.4;
    public String systemPrompt = DEFAULT_SYSTEM_PROMPT;
    public boolean autoRunSafe = true;

    public AiConfig copy() {
        AiConfig out = new AiConfig();
        out.provider = provider;
        out.baseUrl = baseUrl;
        out.model = model;
        out.apiKey = apiKey;
        out.temperature = temperature;
        out.systemPrompt = systemPrompt;
        out.autoRunSafe = autoRunSafe;
        return out;
    }
}
