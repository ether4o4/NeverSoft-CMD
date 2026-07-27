package com.neversoft.shell;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One execution environment for both the visible terminal and the AI agent.
 *
 * NeverSoft stores its real files under its own app sandbox. A small proot
 * compatibility wrapper exposes that same prefix at Termux's historical
 * absolute path so stock Termux packages with compiled-in paths remain usable.
 * There is no second Linux rootfs: both views operate on the same HOME/PREFIX.
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

    public static synchronized void ensureCompatibility(Context context) throws Exception {
        if (proot(context).isFile()) return;

        File p = prefix(context);
        File t = tmp(context);
        t.mkdirs();
        String qPrefix = shQuote(p.getAbsolutePath());
        String qTmp = shQuote(t.getAbsolutePath());
        String command =
            "set -e\n" +
            "cd " + qTmp + "\n" +
            "rm -rf _ns_proot *.deb\n" +
            "apt-get update --allow-insecure-repositories\n" +
            "apt-get download --allow-unauthenticated proot libtalloc\n" +
            "mkdir -p _ns_proot\n" +
            "for deb in *.deb; do dpkg-deb -x \"$deb\" _ns_proot; done\n" +
            "if [ -d _ns_proot" + STOCK_PREFIX + " ]; then cp -a _ns_proot" + STOCK_PREFIX + "/. " + qPrefix + "/; " +
            "elif [ -d _ns_proot/usr ]; then cp -a _ns_proot/usr/. " + qPrefix + "/; else exit 31; fi\n" +
            "chmod 700 " + qPrefix + "/bin/proot 2>/dev/null || true\n" +
            "rm -rf _ns_proot *.deb\n";

        Result result = runBase(context, command);
        if (result.exitCode != 0 || !proot(context).isFile()) {
            throw new IllegalStateException("Unable to install NeverSoft compatibility layer (exit " + result.exitCode + ")\n" + result.output);
        }
    }

    public static Result run(Context context, String command) throws Exception {
        ensureCompatibility(context);
        File p = prefix(context);
        File h = home(context);
        File proot = proot(context);

        List<String> args = new ArrayList<>();
        args.add(proot.getAbsolutePath());
        args.add("-0");
        args.add("-b"); args.add(p.getAbsolutePath() + ":" + STOCK_PREFIX);
        args.add("-b"); args.add(h.getAbsolutePath() + ":" + STOCK_HOME);
        args.add("-b"); args.add("/dev");
        args.add("-b"); args.add("/proc");
        args.add("-b"); args.add("/sys");
        args.add("-w"); args.add(h.getAbsolutePath());
        args.add(new File(p, "bin/bash").getAbsolutePath());
        args.add("-lc");
        args.add(command);

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(h);
        pb.redirectErrorStream(true);
        applyEnvironment(pb.environment(), context);
        return capture(pb);
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
        Result result = run(context,
            "export DEBIAN_FRONTEND=noninteractive; apt-get update --allow-insecure-repositories && " +
            "apt-get install -y --allow-unauthenticated " + list);
        if (result.exitCode != 0) {
            throw new IllegalStateException("Package install failed: " + list + "\n" + result.output);
        }
    }

    public static String terminalExecutable(Context context) {
        File proot = proot(context);
        return proot.isFile() ? proot.getAbsolutePath() : new File(prefix(context), "bin/bash").getAbsolutePath();
    }

    public static String[] terminalArgs(Context context) {
        File p = prefix(context);
        File h = home(context);
        File proot = proot(context);
        File bash = new File(p, "bin/bash");
        if (!proot.isFile()) return new String[] { bash.getAbsolutePath(), "-l" };
        return new String[] {
            proot.getAbsolutePath(),
            "-0",
            "-b", p.getAbsolutePath() + ":" + STOCK_PREFIX,
            "-b", h.getAbsolutePath() + ":" + STOCK_HOME,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-w", h.getAbsolutePath(),
            bash.getAbsolutePath(), "-l"
        };
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
        env.put("PROOT_TMP_DIR", t.getAbsolutePath());
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

    private static Result runBase(Context context, String command) throws Exception {
        File p = prefix(context);
        File h = home(context);
        ProcessBuilder pb = new ProcessBuilder(new File(p, "bin/bash").getAbsolutePath(), "-lc", command);
        pb.directory(h);
        pb.redirectErrorStream(true);
        applyEnvironment(pb.environment(), context);
        return capture(pb);
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

    private static String shQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
