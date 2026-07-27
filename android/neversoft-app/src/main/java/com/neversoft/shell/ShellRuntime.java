package com.neversoft.shell;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Map;

/**
 * One real execution environment for the visible terminal and the AI agent.
 *
 * Device testing showed that Android may deny execution through the historical
 * /data/data/com.termux path even when a proot bind alias is present. NeverSoft
 * therefore executes directly from its own prefix and repairs relocatable script
 * paths/configuration on startup and after overlay package installs.
 */
public final class ShellRuntime {
    public static final String STOCK_PREFIX = "/data/data/com.termux/files/usr";
    public static final String STOCK_HOME = "/data/data/com.termux/files/home";

    public static final class Result {
        public final int exitCode;
        public final String output;
        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private ShellRuntime() {}

    public static File prefix(Context context) { return new File(context.getFilesDir(), "usr"); }
    public static File home(Context context) { return new File(context.getFilesDir(), "home"); }
    public static File tmp(Context context) { return new File(prefix(context), "tmp"); }
    public static File proot(Context context) { return new File(prefix(context), "bin/proot"); }

    /**
     * Kept as the compatibility hook used by BootstrapInstaller. The current
     * compatibility strategy is direct relocation, not a second rootfs/proot shell.
     */
    public static synchronized void ensureCompatibility(Context context) throws Exception {
        RuntimeRepair.repair(context);
    }

    public static Result run(Context context, String command) throws Exception {
        return capture(processBuilder(context, command));
    }

    /** Build a long-lived process in the exact same HOME/PREFIX the terminal uses. */
    public static ProcessBuilder processBuilder(Context context, String command) throws Exception {
        RuntimeRepair.repair(context);
        File p = prefix(context);
        File h = home(context);
        File bash = new File(p, "bin/bash");
        ProcessBuilder pb = new ProcessBuilder(bash.getAbsolutePath(), "--noprofile", "--norc", "-c", command);
        pb.directory(h);
        pb.redirectErrorStream(true);
        applyEnvironment(pb.environment(), context);
        return pb;
    }

    public static void ensurePackages(Context context, String... packages) throws Exception {
        if (packages == null || packages.length == 0) return;
        StringBuilder list = new StringBuilder();
        for (String pkg : packages) {
            if (pkg == null || !pkg.matches("[A-Za-z0-9.+_-]+")) {
                throw new IllegalArgumentException("Invalid package name: " + pkg);
            }
            if (list.length() > 0) list.append(' ');
            list.append(pkg);
        }
        Result result = run(context, "pkg install " + list);
        if (result.exitCode != 0) {
            throw new IllegalStateException("Package install failed: " + list + "\n" + result.output);
        }
    }

    public static String terminalExecutable(Context context) {
        try {
            RuntimeRepair.repair(context);
        } catch (Exception ignored) {
            // Bootstrap errors are surfaced separately; keep terminal startup best-effort.
        }
        return new File(prefix(context), "bin/bash").getAbsolutePath();
    }

    public static String[] terminalArgs(Context context) {
        File bash = new File(prefix(context), "bin/bash");
        // Official Termux bash has compiled system startup paths under com.termux.
        // NeverSoft supplies its environment directly, so bypass those files entirely.
        return new String[] { bash.getAbsolutePath(), "--noprofile", "--norc", "-i" };
    }

    public static void applyEnvironment(Map<String, String> env, Context context) {
        File p = prefix(context);
        File h = home(context);
        File t = tmp(context);
        String prefix = p.getAbsolutePath();
        env.clear();
        env.put("HOME", h.getAbsolutePath());
        env.put("PREFIX", prefix);
        env.put("TMPDIR", t.getAbsolutePath());
        env.put("TMP", t.getAbsolutePath());
        env.put("TEMP", t.getAbsolutePath());
        env.put("PATH", prefix + "/bin:" + prefix + "/bin/applets:/system/bin:/system/xbin");
        env.put("SHELL", prefix + "/bin/bash");
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.put("LANG", "en_US.UTF-8");
        env.put("EXTERNAL_STORAGE", "/storage/emulated/0");
        env.put("LD_LIBRARY_PATH", prefix + "/lib");
        env.put("LD_PRELOAD", prefix + "/lib/libtermux-exec.so");
        env.put("APT_CONFIG", prefix + "/etc/apt/apt.conf");
        env.put("DPKG_ADMINDIR", prefix + "/var/lib/dpkg");
        env.put("SSL_CERT_FILE", prefix + "/etc/tls/cert.pem");
        env.put("SSL_CERT_DIR", "/system/etc/security/cacerts");
        env.put("CURL_CA_BUNDLE", prefix + "/etc/tls/cert.pem");
        env.put("GIT_SSL_CAINFO", prefix + "/etc/tls/cert.pem");
        env.put("GIT_EXEC_PATH", prefix + "/libexec/git-core");
        env.put("TERMUX_PREFIX", prefix);
        env.put("TERMUX__PREFIX", prefix);
        env.put("TERMUX__HOME", h.getAbsolutePath());
        env.put("TERMUX_APP__PACKAGE_NAME", context.getPackageName());
        env.put("TERMUX_APP__DATA_DIR", context.getApplicationInfo().dataDir);
    }

    private static Result capture(ProcessBuilder pb) throws Exception {
        java.lang.Process process = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() < 64 * 1024) out.append(line).append('\n');
            }
        }
        int exit = process.waitFor();
        return new Result(exit, out.toString().trim());
    }
}
