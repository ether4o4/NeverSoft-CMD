package com.neversoft.shell.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Native port of the useful safety classifier from shell-ai-scripts.
 *
 * This is intended for AI-initiated commands and scripted automations. Direct
 * interactive terminal input remains a real shell and is not artificially
 * sandboxed by this class.
 */
public final class CommandRisk {
    public enum Level { SAFE, CAUTION, DESTRUCTIVE }

    public static final class Result {
        public final Level level;
        public final List<String> reasons;

        Result(Level level, List<String> reasons) {
            this.level = level;
            this.reasons = Collections.unmodifiableList(reasons);
        }
    }

    private static final class Rule {
        final Pattern pattern;
        final String reason;
        Rule(String regex, String reason) {
            this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            this.reason = reason;
        }
    }

    private static final Rule[] DESTRUCTIVE = new Rule[] {
        new Rule("\\brm\\s+(-[a-z]*\\s+)*", "Deletes files or directories (rm)"),
        new Rule("\\brmdir\\b", "Removes a directory (rmdir)"),
        new Rule("\\bmv\\b", "Moves/renames and may overwrite existing files (mv)"),
        new Rule(">\\s*[^>\\s]", "Redirect may overwrite a file (>)"),
        new Rule("\\bdd\\b", "Low-level data copy/write (dd)"),
        new Rule("\\bmkfs\\b|\\bformat\\b", "Formats a filesystem"),
        new Rule("\\btruncate\\b", "Truncates file contents"),
        new Rule("\\bchmod\\s+-R\\b|\\bchown\\s+-R\\b", "Recursive permission/ownership change"),
        new Rule("\\bkill(all)?\\b|\\bpkill\\b", "Terminates processes"),
        new Rule("\\b(shutdown|reboot|poweroff)\\b", "Requests shutdown/reboot"),
        new Rule(":\\(\\)\\s*\\{.*\\};:", "Fork bomb pattern detected"),
        new Rule("\\bgit\\s+reset\\s+--hard\\b|\\bgit\\s+clean\\b", "Destructive git operation")
    };

    private static final Rule[] CAUTION = new Rule[] {
        new Rule("\\bsu(do)?\\b", "Elevated/root privileges requested"),
        new Rule("\\bcurl\\b.*\\|\\s*(sh|bash)\\b", "Pipes remote content directly to a shell"),
        new Rule("\\bwget\\b.*\\|\\s*(sh|bash)\\b", "Pipes remote content directly to a shell"),
        new Rule("\\b(pip|pip3)\\s+install\\b|\\bnpm\\s+install\\b|\\bpkg\\s+install\\b|\\bapt(-get)?\\s+install\\b", "Installs software/packages"),
        new Rule("\\bcp\\s+-[a-z]*f\\b", "Force-copy may overwrite files")
    };

    private CommandRisk() {}

    public static Result analyze(String command) {
        String cmd = command == null ? "" : command.trim();
        List<String> reasons = new ArrayList<>();
        Level level = Level.SAFE;

        for (Rule rule : DESTRUCTIVE) {
            if (rule.pattern.matcher(cmd).find()) {
                reasons.add(rule.reason);
                level = Level.DESTRUCTIVE;
            }
        }

        if (level != Level.DESTRUCTIVE) {
            for (Rule rule : CAUTION) {
                if (rule.pattern.matcher(cmd).find()) {
                    reasons.add(rule.reason);
                    level = Level.CAUTION;
                }
            }
        }

        return new Result(level, reasons);
    }
}
