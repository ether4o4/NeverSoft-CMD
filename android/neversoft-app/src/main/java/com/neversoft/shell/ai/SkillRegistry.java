package com.neversoft.shell.ai;

import android.content.Context;

import com.neversoft.shell.ShellRuntime;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** CClaw-inspired skill registry backed by ~/skills. */
public final class SkillRegistry {
    public enum Risk { READ_ONLY, WRITE, DESTRUCTIVE }

    public static final class Skill {
        public final String id;
        public final String name;
        public final String description;
        public final Risk risk;
        public final String command;
        public final List<String> requires;

        Skill(String id, String name, String description, Risk risk, String command, List<String> requires) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.risk = risk;
            this.command = command;
            this.requires = Collections.unmodifiableList(requires);
        }
    }

    public static final class Result {
        public final boolean success;
        public final String output;
        public final Risk risk;
        Result(boolean success, String output, Risk risk) {
            this.success = success;
            this.output = output;
            this.risk = risk;
        }
    }

    private final Context context;
    private final AgentMemory memory;
    private final File skillsDir;
    private final List<Skill> skills = new ArrayList<>();

    public SkillRegistry(Context context, AgentMemory memory) {
        this.context = context.getApplicationContext();
        this.memory = memory;
        this.skillsDir = new File(ShellRuntime.home(context), "skills");
        ensureStarterSkills();
        reload();
    }

    public synchronized void reload() {
        skills.clear();
        addBuiltins();
        File[] dirs = skillsDir.listFiles();
        if (dirs == null) return;
        for (File dir : dirs) {
            File spec = new File(dir, "skill.json");
            if (!spec.isFile()) continue;
            try {
                JSONObject json = new JSONObject(readText(spec));
                String id = json.optString("id", dir.getName());
                String name = json.optString("name", id);
                String description = json.optString("description", "");
                Risk risk;
                try { risk = Risk.valueOf(json.optString("risk", "READ_ONLY").toUpperCase()); }
                catch (Exception ignored) { risk = Risk.READ_ONLY; }
                String command = json.optString("command", "");
                List<String> requires = new ArrayList<>();
                JSONArray req = json.optJSONArray("requires");
                if (req != null) for (int i = 0; i < req.length(); i++) requires.add(req.optString(i));
                skills.add(new Skill(id, name, description, risk, command, requires));
            } catch (Exception ignored) {
                // A malformed user skill should not break the AI pane.
            }
        }
    }

    public synchronized List<Skill> list() { return new ArrayList<>(skills); }

    public synchronized Skill find(String id) {
        for (Skill skill : skills) if (skill.id.equals(id)) return skill;
        return null;
    }

    public synchronized String describeForPrompt() {
        StringBuilder out = new StringBuilder();
        out.append("\n\nNEVERSOFT SKILLS:\n");
        out.append("To call a skill, emit a fenced skill block containing JSON, e.g.\n");
        out.append("```skill\n{\"id\":\"file.read\",\"args\":{\"path\":\"~/notes.txt\"}}\n```\n");
        out.append("Available skills:\n");
        for (Skill skill : skills) {
            out.append("- ").append(skill.id).append(" [").append(skill.risk).append("] — ")
                .append(skill.description == null ? "" : skill.description).append('\n');
        }
        out.append("Prefer structured skills over shell when an appropriate skill exists. Use run blocks for general shell work.\n");
        return out.toString();
    }

    public Result execute(String id, JSONObject args) throws Exception {
        Skill skill = find(id);
        if (skill == null) return new Result(false, "Unknown skill: " + id, Risk.READ_ONLY);
        if (args == null) args = new JSONObject();

        switch (id) {
            case "skill.list":
                return new Result(true, describeForPrompt(), Risk.READ_ONLY);
            case "file.read":
                return readFile(args.optString("path", ""));
            case "file.write":
                return writeFile(args.optString("path", ""), args.optString("content", ""));
            case "memory.store":
                memory.store(args.optString("category", "custom"), args.optString("content", ""));
                return new Result(true, "Memory stored.", Risk.WRITE);
            case "memory.search":
                return new Result(true, join(memory.search(args.optString("query", ""), args.optInt("limit", 5))), Risk.READ_ONLY);
            default:
                return executeDynamic(skill, args);
        }
    }

    private Result readFile(String rawPath) throws Exception {
        File file = resolvePath(rawPath);
        if (!file.isFile()) return new Result(false, "File not found: " + file, Risk.READ_ONLY);
        if (file.length() > 2L * 1024L * 1024L) return new Result(false, "File is larger than 2 MB.", Risk.READ_ONLY);
        return new Result(true, readText(file), Risk.READ_ONLY);
    }

    private Result writeFile(String rawPath, String content) throws Exception {
        File file = resolvePath(rawPath);
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return new Result(true, "Wrote " + content.getBytes(StandardCharsets.UTF_8).length + " bytes to " + file, Risk.WRITE);
    }

    private Result executeDynamic(Skill skill, JSONObject args) throws Exception {
        if (skill.command == null || skill.command.trim().isEmpty()) {
            return new Result(false, "Skill has no command template: " + skill.id, skill.risk);
        }
        for (String required : skill.requires) {
            ShellRuntime.Result check = ShellRuntime.run(context, "command -v " + shellWord(required) + " >/dev/null 2>&1");
            if (check.exitCode != 0) {
                return new Result(false, "Missing dependency '" + required + "' for " + skill.id + ". Install it first.", skill.risk);
            }
        }
        String rendered = skill.command;
        java.util.Iterator<String> keys = args.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            rendered = rendered.replace("{{" + key + "}}", shQuote(args.optString(key, "")));
        }
        if (rendered.contains("{{")) return new Result(false, "Skill arguments are incomplete for " + skill.id, skill.risk);
        ShellRuntime.Result result = ShellRuntime.run(context, rendered);
        return new Result(result.exitCode == 0,
            (result.output == null || result.output.isEmpty() ? "(no output)" : result.output) + "\n[exit=" + result.exitCode + "]",
            skill.risk);
    }

    private File resolvePath(String raw) throws Exception {
        String path = raw == null ? "" : raw.trim();
        File home = ShellRuntime.home(context);
        if (path.isEmpty() || "~".equals(path)) return home.getCanonicalFile();
        if (path.startsWith("~/")) return new File(home, path.substring(2)).getCanonicalFile();
        File file = new File(path);
        if (!file.isAbsolute()) file = new File(home, path);
        return file.getCanonicalFile();
    }

    private void addBuiltins() {
        skills.add(new Skill("skill.list", "List skills", "List all NeverSoft skills and their risk levels.", Risk.READ_ONLY, "", Collections.emptyList()));
        skills.add(new Skill("file.read", "Read file", "Read a local file without shell syntax.", Risk.READ_ONLY, "", Collections.emptyList()));
        skills.add(new Skill("file.write", "Write file", "Create or replace a local file.", Risk.WRITE, "", Collections.emptyList()));
        skills.add(new Skill("memory.store", "Store memory", "Save durable local agent memory.", Risk.WRITE, "", Collections.emptyList()));
        skills.add(new Skill("memory.search", "Search memory", "Search durable NeverSoft agent memory.", Risk.READ_ONLY, "", Collections.emptyList()));
    }

    private void ensureStarterSkills() {
        skillsDir.mkdirs();
        writeStarter("osint-sherlock", "{\n  \"id\": \"osint.username.sherlock\",\n  \"name\": \"Sherlock username search\",\n  \"description\": \"Search a username across supported public sites using Sherlock.\",\n  \"risk\": \"READ_ONLY\",\n  \"requires\": [\"sherlock\"],\n  \"command\": \"sherlock --print-found {{username}}\"\n}\n");
        writeStarter("osint-maigret", "{\n  \"id\": \"osint.username.maigret\",\n  \"name\": \"Maigret username search\",\n  \"description\": \"Run a Maigret username search and return terminal results.\",\n  \"risk\": \"READ_ONLY\",\n  \"requires\": [\"maigret\"],\n  \"command\": \"maigret {{username}} --no-progressbar\"\n}\n");
        writeStarter("osint-holehe", "{\n  \"id\": \"osint.email.holehe\",\n  \"name\": \"Holehe email search\",\n  \"description\": \"Check public account-registration signals for an email using Holehe.\",\n  \"risk\": \"READ_ONLY\",\n  \"requires\": [\"holehe\"],\n  \"command\": \"holehe {{email}} --only-used\"\n}\n");
        writeStarter("osint-phoneinfoga", "{\n  \"id\": \"osint.phone.phoneinfoga\",\n  \"name\": \"PhoneInfoga phone lookup\",\n  \"description\": \"Run PhoneInfoga against a phone number for public-source intelligence.\",\n  \"risk\": \"READ_ONLY\",\n  \"requires\": [\"phoneinfoga\"],\n  \"command\": \"phoneinfoga scan -n {{number}}\"\n}\n");
    }

    private void writeStarter(String dirName, String json) {
        try {
            File dir = new File(skillsDir, dirName);
            dir.mkdirs();
            File spec = new File(dir, "skill.json");
            if (!spec.exists()) writeText(spec, json);
            File doc = new File(dir, "SKILL.md");
            if (!doc.exists()) writeText(doc, "# " + dirName + "\n\nGenerated NeverSoft starter skill. Edit skill.json to customize it.\n");
        } catch (Exception ignored) {}
    }

    private static String join(List<String> items) {
        if (items == null || items.isEmpty()) return "No matching memories.";
        StringBuilder out = new StringBuilder();
        for (String item : items) out.append(item).append('\n');
        return out.toString().trim();
    }

    private static String readText(File file) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
        }
        return out.toString();
    }

    private static void writeText(File file, String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String shellWord(String value) {
        return value != null && value.matches("[A-Za-z0-9._+-]+") ? value : "false";
    }

    private static String shQuote(String value) {
        return "'" + (value == null ? "" : value).replace("'", "'\\''") + "'";
    }
}
