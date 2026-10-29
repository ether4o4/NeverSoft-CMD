package com.neversoft.shell.ai;

import android.app.Activity;

import org.json.JSONObject;

import java.util.List;

/** Local agent loop: Hugging Face model + NeverSoft skills + SQLite memory. */
public final class NeverSoftAgentRuntime {
    public interface Ui {
        void status(String value);
        void append(String value);
    }

    private final AgentMemory memory;
    private final SkillRegistry skills;
    private final AgentActionRuntime actions;
    private String lastUserText = "";

    public NeverSoftAgentRuntime(Activity activity) {
        memory = new AgentMemory(activity);
        skills = new SkillRegistry(activity, memory);
        actions = new AgentActionRuntime(activity, skills);
    }

    public void close() { memory.close(); }
    public String modeLabel() { return actions.modeLabel(); }
    public void cycleMode() { actions.cycleMode(); }

    public void rememberUser(String text) {
        lastUserText = text == null ? "" : text;
        memory.store("conversation", "USER: " + lastUserText);
    }

    public String systemPrompt(AiConfig config) {
        StringBuilder prompt = new StringBuilder(config.systemPrompt);
        prompt.append("\nCurrent autonomy mode: ").append(actions.getMode()).append(".\n");
        prompt.append(skills.describeForPrompt());
        List<String> remembered = memory.search(lastUserText, 4);
        if (!remembered.isEmpty()) {
            prompt.append("\nRELEVANT LOCAL MEMORY:\n");
            for (String item : remembered) prompt.append("- ").append(item).append('\n');
        }
        return prompt.toString();
    }

    /** Execute every action block in one model response and feed results to history. */
    public boolean executeActions(String full, List<JSONObject> history, Ui ui) throws Exception {
        boolean handled = false;

        for (AgentProtocol.SkillCall call : AgentProtocol.parseSkillBlocks(full)) {
            handled = true;
            ui.status("HF LOCAL · skill " + call.id + "…");
            SkillRegistry.Result result = actions.executeSkill(call);
            String output = trim(result.output);
            ui.append("SKILL " + call.id + "\n" + output + "\n");
            history.add(message("user", "[skill output: " + call.id + ", success=" + result.success + "]\n" + output));
        }

        List<String> commands = AgentProtocol.parseRunBlocks(full);
        if (!commands.isEmpty()) handled = true;
        for (String command : commands) {
            ui.status("HF LOCAL · working…");
            ui.append("$ " + command + "\n");
            AgentActionRuntime.ShellResult result = actions.executeShell(command);
            String output = trim(result.output == null || result.output.isEmpty() ? "(no output)" : result.output);
            ui.append(output + "\n");
            history.add(message("user", "[shell output, exit=" + result.exitCode + "]\n" + output));
        }
        return handled;
    }

    public void rememberAssistant(String full) {
        String visible = AgentProtocol.stripActionBlocks(full);
        if (!visible.isEmpty()) memory.store("conversation", "ASSISTANT: " + visible);
    }

    private static JSONObject message(String role, String content) {
        try { return new JSONObject().put("role", role).put("content", content); }
        catch (Exception impossible) { return new JSONObject(); }
    }

    private static String trim(String value) {
        if (value == null || value.isEmpty()) return "(no output)";
        return value.length() > 6000 ? value.substring(0, 6000) + "\n…(truncated)" : value;
    }
}
