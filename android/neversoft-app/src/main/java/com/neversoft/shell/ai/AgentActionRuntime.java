package com.neversoft.shell.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;

import com.neversoft.shell.ShellRuntime;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** Executes AI-requested skills and shell commands under NeverSoft autonomy policy. */
public final class AgentActionRuntime {
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SkillRegistry skills;
    private AiConfig.AutonomyMode mode = AiConfig.AutonomyMode.SUPERVISED;

    public AgentActionRuntime(Activity activity, SkillRegistry skills) {
        this.activity = activity;
        this.skills = skills;
    }

    public AiConfig.AutonomyMode getMode() { return mode; }

    public void cycleMode() {
        switch (mode) {
            case READ_ONLY: mode = AiConfig.AutonomyMode.SUPERVISED; break;
            case SUPERVISED: mode = AiConfig.AutonomyMode.FULL; break;
            default: mode = AiConfig.AutonomyMode.READ_ONLY; break;
        }
    }

    public String modeLabel() {
        switch (mode) {
            case READ_ONLY: return "Read only";
            case FULL: return "Full";
            default: return "Supervised";
        }
    }

    public SkillRegistry.Result executeSkill(AgentProtocol.SkillCall call) throws Exception {
        SkillRegistry.Skill skill = skills.find(call.id);
        if (skill == null) return new SkillRegistry.Result(false, "Unknown skill: " + call.id, SkillRegistry.Risk.READ_ONLY);
        if (mode == AiConfig.AutonomyMode.READ_ONLY && skill.risk != SkillRegistry.Risk.READ_ONLY) {
            return new SkillRegistry.Result(false, "Blocked by read-only mode.", skill.risk);
        }
        if (skill.risk != SkillRegistry.Risk.READ_ONLY && !approve("Skill: " + skill.id, "Risk: " + skill.risk)) {
            return new SkillRegistry.Result(false, "Blocked by user.", skill.risk);
        }
        return skills.execute(call.id, call.args);
    }

    public ShellRuntime.Result executeShell(String command) throws Exception {
        CommandRisk.Result risk = CommandRisk.analyze(command);
        if (mode == AiConfig.AutonomyMode.READ_ONLY && (!isReadOnlyCommand(command) || risk.level != CommandRisk.Level.SAFE)) {
            return new ShellRuntime.Result(126, "Blocked by read-only mode.");
        }
        // Full mode auto-runs safe/caution actions, but destructive operations still ask.
        boolean needsApproval = risk.level == CommandRisk.Level.DESTRUCTIVE ||
            (mode == AiConfig.AutonomyMode.SUPERVISED && risk.level == CommandRisk.Level.CAUTION);
        if (needsApproval) {
            String reason = risk.reasons.isEmpty() ? risk.level.toString() : join(risk.reasons);
            if (!approve(command, reason)) return new ShellRuntime.Result(126, "Blocked by user.");
        }
        return ShellRuntime.run(activity, command);
    }

    private boolean isReadOnlyCommand(String command) {
        String cmd = command == null ? "" : command.trim().toLowerCase();
        String[] allowed = {"pwd", "ls", "cat", "head", "tail", "grep", "find", "which", "command -v", "git status", "git log", "git diff", "python --version", "pip --version", "pkg search", "pkg show", "ps", "env", "printenv", "uname", "id"};
        for (String prefix : allowed) if (cmd.equals(prefix) || cmd.startsWith(prefix + " ")) return true;
        return false;
    }

    private boolean approve(String action, String reason) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean approved = new AtomicBoolean(false);
        main.post(() -> new AlertDialog.Builder(activity)
            .setTitle("Allow AI action?")
            .setMessage(action + "\n\n" + reason)
            .setNegativeButton("Block", (d, w) -> latch.countDown())
            .setPositiveButton("Run", (d, w) -> { approved.set(true); latch.countDown(); })
            .setOnCancelListener(d -> latch.countDown())
            .show());
        latch.await();
        return approved.get();
    }

    private static String join(List<String> items) {
        StringBuilder out = new StringBuilder();
        for (String item : items) {
            if (out.length() > 0) out.append('\n');
            out.append(item);
        }
        return out.toString();
    }
}
