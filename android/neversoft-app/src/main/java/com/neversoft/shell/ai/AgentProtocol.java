package com.neversoft.shell.ai;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provider-agnostic command/skill protocol for NeverSoft local agents.
 * Models can request native shell actions with fenced ```run blocks or
 * structured NeverSoft skills with fenced ```skill JSON blocks.
 */
public final class AgentProtocol {
    public static final int DEFAULT_MAX_ITERATIONS = 8;

    private static final Pattern RUN_BLOCK =
        Pattern.compile("```run\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern SKILL_BLOCK =
        Pattern.compile("```skill\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private AgentProtocol() {}

    public static List<String> parseRunBlocks(String text) {
        List<String> commands = new ArrayList<>();
        if (text == null || text.isEmpty()) return commands;
        Matcher matcher = RUN_BLOCK.matcher(text);
        while (matcher.find()) {
            String body = matcher.group(1);
            if (body == null) continue;
            for (String line : body.split("\\r?\\n")) {
                String command = line.trim();
                if (command.isEmpty() || command.startsWith("#")) continue;
                commands.add(command);
            }
        }
        return commands;
    }

    public static List<SkillCall> parseSkillBlocks(String text) {
        List<SkillCall> calls = new ArrayList<>();
        if (text == null || text.isEmpty()) return calls;
        Matcher matcher = SKILL_BLOCK.matcher(text);
        while (matcher.find()) {
            try {
                JSONObject json = new JSONObject(matcher.group(1).trim());
                String id = json.optString("id", "").trim();
                if (id.isEmpty()) continue;
                JSONObject args = json.optJSONObject("args");
                if (args == null) args = new JSONObject();
                calls.add(new SkillCall(id, args));
            } catch (Exception ignored) {}
        }
        return calls;
    }

    public static String stripActionBlocks(String text) {
        if (text == null || text.isEmpty()) return "";
        return SKILL_BLOCK.matcher(RUN_BLOCK.matcher(text).replaceAll("")).replaceAll("").trim();
    }

    public static String stripRunBlocks(String text) {
        if (text == null || text.isEmpty()) return "";
        return RUN_BLOCK.matcher(text).replaceAll("").trim();
    }

    public static String formatCommandResults(List<CommandResult> results) {
        StringBuilder out = new StringBuilder("Command output:\n");
        for (int i = 0; i < results.size(); i++) {
            CommandResult result = results.get(i);
            if (i > 0) out.append("\n\n");
            out.append("$ ").append(result.command).append('\n');
            if (!result.approved) out.append("[blocked by user]");
            else if (result.output == null || result.output.isEmpty()) out.append("(no output)");
            else out.append(result.output);
        }
        return out.toString();
    }

    public static final class SkillCall {
        public final String id;
        public final JSONObject args;
        public SkillCall(String id, JSONObject args) {
            this.id = id;
            this.args = args == null ? new JSONObject() : args;
        }
    }

    public static final class CommandResult {
        public final String command;
        public final String output;
        public final boolean approved;
        public final int exitCode;

        public CommandResult(String command, String output, boolean approved, int exitCode) {
            this.command = command;
            this.output = output;
            this.approved = approved;
            this.exitCode = exitCode;
        }
    }
}
