package com.neversoft.shell;

import android.content.Context;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Repairs text-level Termux prefix assumptions after the official bootstrap is
 * extracted into NeverSoft's own Android sandbox.
 *
 * Android 16 device testing showed that executing scripts through the historical
 * /data/data/com.termux path is not reliable even when a proot bind alias is
 * present. NeverSoft therefore runs from its real prefix and rewrites script/config
 * paths that are safe to relocate. ELF binaries continue to use the normal Android
 * linker with NeverSoft's lib directory in LD_LIBRARY_PATH.
 */
public final class RuntimeRepair {
    private static final String STOCK_PREFIX = "/data/data/com.termux/files/usr";
    private static boolean repairedThisProcess;

    private RuntimeRepair() {}

    public static synchronized void repair(Context context) throws Exception {
        File prefix = ShellRuntime.prefix(context);
        if (!prefix.isDirectory()) return;

        String actual = prefix.getAbsolutePath();
        fixAptConfig(prefix, actual);
        patchKnownText(prefix, actual);
        patchShebangTree(new File(prefix, "bin"), actual);
        patchShebangTree(new File(prefix, "libexec"), actual);
        writeRuntimeHelpers(prefix, actual);
        repairedThisProcess = true;
    }

    public static boolean isRepairedThisProcess() {
        return repairedThisProcess;
    }

    private static void fixAptConfig(File prefix, String actual) throws Exception {
        File aptDir = new File(prefix, "etc/apt");
        aptDir.mkdirs();
        File aptConf = new File(aptDir, "apt.conf");
        String current = aptConf.isFile() ? readText(aptConf) : "";
        StringBuilder out = new StringBuilder();
        if (!current.isEmpty()) out.append(current.trim()).append('\n');
        if (!current.contains("Dir::Bin::apt-key")) {
            out.append("Dir::Bin::apt-key \"").append(actual).append("/bin/apt-key\";\n");
        }
        if (!current.contains("Dir::Bin::dpkg")) {
            out.append("Dir::Bin::dpkg \"").append(actual).append("/bin/dpkg\";\n");
        }
        writeText(aptConf, out.toString());
    }

    private static void patchKnownText(File prefix, String actual) throws Exception {
        patchTextFile(new File(prefix, "etc/profile"), actual);
        patchTextFile(new File(prefix, "etc/bash.bashrc"), actual);
        patchTextTree(new File(prefix, "etc/profile.d"), actual);

        String[] scripts = {
            "bin/apt-key",
            "bin/ghget",
            "bin/github-install",
            "bin/storage-setup",
            "bin/hf-serve"
        };
        for (String path : scripts) patchTextFile(new File(prefix, path), actual);
    }

    private static void patchTextTree(File root, String actual) throws Exception {
        if (!root.isDirectory()) return;
        File[] files = root.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) patchTextTree(file, actual);
            else if (file.length() <= 1024 * 1024) patchTextFile(file, actual);
        }
    }

    private static void patchShebangTree(File root, String actual) throws Exception {
        if (!root.isDirectory()) return;
        File[] files = root.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                patchShebangTree(file, actual);
                continue;
            }
            if (file.length() <= 0 || file.length() > 2L * 1024L * 1024L) continue;
            byte[] head = new byte[2];
            try (FileInputStream in = new FileInputStream(file)) {
                if (in.read(head) != 2 || head[0] != '#' || head[1] != '!') continue;
            }
            patchTextFile(file, actual);
        }
    }

    private static void patchTextFile(File file, String actual) throws Exception {
        if (!file.isFile() || file.length() > 2L * 1024L * 1024L) return;
        String text;
        try {
            text = readText(file);
        } catch (Exception ignored) {
            return;
        }
        if (!text.contains(STOCK_PREFIX)) return;
        writeText(file, text.replace(STOCK_PREFIX, actual));
        if (text.startsWith("#!")) Os.chmod(file.getAbsolutePath(), 0700);
    }

    private static void writeRuntimeHelpers(File prefix, String actual) throws Exception {
        File bin = new File(prefix, "bin");
        bin.mkdirs();
        String bash = actual + "/bin/bash";

        String fixPaths = "#!" + bash + "\n" +
            "set -e\n" +
            "old='/data/data/com.termux/files/usr'\n" +
            "new=\"$PREFIX\"\n" +
            "for root in \"$PREFIX/bin\" \"$PREFIX/libexec\"; do\n" +
            "  [ -d \"$root\" ] || continue\n" +
            "  find \"$root\" -type f -size -2048k 2>/dev/null | while IFS= read -r f; do\n" +
            "    [ \"$(head -c 2 \"$f\" 2>/dev/null || true)\" = '#!' ] || continue\n" +
            "    head -n 1 \"$f\" 2>/dev/null | grep -Fq \"$old\" || continue\n" +
            "    sed -i \"1s|$old|$new|g\" \"$f\"\n" +
            "    chmod 700 \"$f\" 2>/dev/null || true\n" +
            "  done\n" +
            "done\n" +
            "for f in \"$PREFIX/etc/profile\" \"$PREFIX/etc/bash.bashrc\"; do\n" +
            "  [ -f \"$f\" ] && grep -Fq \"$old\" \"$f\" && sed -i \"s|$old|$new|g\" \"$f\" || true\n" +
            "done\n";
        writeExecutable(new File(bin, "neversoft-fix-paths"), fixPaths);

        String shell = "#!" + bash + "\n" +
            "exec \"$PREFIX/bin/bash\" -c \"$*\"\n";
        writeExecutable(new File(bin, "neversoft-shell"), shell);

        String pkg = "#!" + bash + "\n" +
            "set -e\n" +
            "cmd=${1:-help}\n" +
            "[ $# -gt 0 ] && shift || true\n" +
            "case \"$cmd\" in\n" +
            "  update|up) exec apt-get update ;;\n" +
            "  search) exec apt-cache search \"$@\" ;;\n" +
            "  show) exec apt-cache show \"$@\" ;;\n" +
            "  install|in)\n" +
            "    [ $# -gt 0 ] || { echo 'pkg install: package name required' >&2; exit 2; }\n" +
            "    for p in \"$@\"; do case \"$p\" in *[!A-Za-z0-9.+_-]*) echo \"Invalid package name: $p\" >&2; exit 2;; esac; done\n" +
            "    cache=\"$PREFIX/var/cache/apt/archives\"\n" +
            "    stage=\"$PREFIX/tmp/.neversoft-pkg-stage\"\n" +
            "    mkdir -p \"$cache/partial\" \"$PREFIX/tmp\"\n" +
            "    rm -rf \"$stage\"; mkdir -p \"$stage\"\n" +
            "    rm -f \"$cache\"/*.deb 2>/dev/null || true\n" +
            "    apt-get update\n" +
            "    apt-get install --download-only -y \"$@\"\n" +
            "    found=0\n" +
            "    for deb in \"$cache\"/*.deb; do\n" +
            "      [ -f \"$deb\" ] || continue\n" +
            "      found=1\n" +
            "      dpkg-deb -x \"$deb\" \"$stage\"\n" +
            "    done\n" +
            "    [ $found -eq 1 ] || { echo 'No packages were downloaded.' >&2; exit 1; }\n" +
            "    if [ -d \"$stage/data/data/com.termux/files/usr\" ]; then\n" +
            "      cp -a \"$stage/data/data/com.termux/files/usr/.\" \"$PREFIX/\"\n" +
            "    elif [ -d \"$stage/usr\" ]; then\n" +
            "      cp -a \"$stage/usr/.\" \"$PREFIX/\"\n" +
            "    else\n" +
            "      echo 'Unsupported package payload layout.' >&2; exit 1\n" +
            "    fi\n" +
            "    rm -rf \"$stage\"\n" +
            "    \"$PREFIX/bin/neversoft-fix-paths\"\n" +
            "    hash -r\n" +
            "    echo \"Installed into NeverSoft prefix: $*\"\n" +
            "    ;;\n" +
            "  remove|uninstall) echo 'Package removal is not enabled in overlay-install mode yet.' >&2; exit 2 ;;\n" +
            "  help|-h|--help) echo 'NeverSoft pkg: update install search show' ;;\n" +
            "  *) echo \"Unknown pkg command: $cmd\" >&2; exit 2 ;;\n" +
            "esac\n";
        writeExecutable(new File(bin, "pkg"), pkg);
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

    private static void writeExecutable(File file, String text) throws Exception {
        writeText(file, text);
        Os.chmod(file.getAbsolutePath(), 0700);
    }
}
