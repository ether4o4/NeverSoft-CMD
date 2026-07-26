package com.neversoft.shell.ai;

/**
 * Provider/model configuration for NeverSoft's future AI pane.
 *
 * Ported from shell-ai-scripts' provider abstraction, but generalized so the
 * native app is not coupled to Expo, Supabase, one model format, or one cloud.
 */
public final class AiConfig {
    public enum Provider {
        OLLAMA,
        OPENAI_COMPATIBLE,
        CLOUD_GATEWAY
    }

    public static final String DEFAULT_SYSTEM_PROMPT =
        "You are NeverSoft AI, an expert Android/Linux shell operator embedded in NeverSoft CMD.\n" +
        "You can request commands by emitting a fenced code block labelled run:\n\n" +
        "```run\n" +
        "ls -la\n" +
        "cat README.md\n" +
        "```\n\n" +
        "Rules:\n" +
        "- Put one command per line inside a run block.\n" +
        "- Inspect before modifying when practical.\n" +
        "- Never hide destructive intent. NeverSoft may require user approval.\n" +
        "- Prefer reversible operations.\n" +
        "- When the task is complete, answer normally without a run block.\n" +
        "- If no shell action is needed, do not emit a run block.";

    public Provider provider = Provider.OPENAI_COMPATIBLE;
    public String baseUrl = "http://127.0.0.1:8080/v1";
    public String model = "";
    public String apiKey = "";
    public double temperature = 0.6;
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
