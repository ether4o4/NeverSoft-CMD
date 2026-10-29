package com.neversoft.shell;

import android.content.Context;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs the bundled Termux bootstrap into NeverSoft's own app sandbox.
 *
 * The fast operational-shell path deliberately reuses a pinned official Termux
 * bootstrap, then relocates config/symlinks into NeverSoft. A tiny proot layer
 * later exposes the same physical prefix at Termux's historical absolute path
 * for third-party packages that still have that path compiled in.
 *
 * Bootstrap extraction/relocation is adapted from Termux and the MIT-licensed
 * OpenClawAndroid standalone bootstrap implementation.
 */
final class BootstrapInstaller {
    private static final String TAG = "BootstrapInstaller";

    interface Callback {
        void onReady();
        void onError(String message, Throwable error);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final String SYMLINKS_FILE = "SYMLINKS.txt";
    private static final String SHARED_STORAGE = "/storage/emulated/0";
    private static final String STOCK_PREFIX = ShellRuntime.STOCK_PREFIX;

    private BootstrapInstaller() {}

    static void ensureInstalled(Context context, Callback callback) {
        final Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                File rootfs = app.getFilesDir();
                File prefix = new File(rootfs, "usr");
                File bash = new File(prefix, "bin/bash");
                if (!bash.isFile()) install(app, rootfs, prefix);
                fixRelocatedBootstrap(prefix);
                installNeverSoftTools(prefix);

                // Compatibility setup needs network access. Failure here must not
                // make the base shell unusable; pkg/AI setup can retry later.
                try {
                    ShellRuntime.ensureCompatibility(app);
                } catch (Throwable compatError) {
                    Log.w(TAG, "Compatibility layer not ready yet; base shell will still open", compatError);
                }
                callback.onReady();
            } catch (Throwable t) {
                callback.onError(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(), t);
            }
        });
    }

    private static void install(Context context, File rootfs, File prefix) throws Exception {
        File staging = new File(rootfs, "usr-staging");
        deleteRecursively(staging);
        if (!staging.mkdirs() && !staging.isDirectory()) {
            throw new IllegalStateException("Cannot create bootstrap staging directory: " + staging);
        }

        List<Symlink> symlinks = new ArrayList<>();
        byte[] buffer = new byte[8192];

        try (InputStream raw = context.getResources().openRawResource(R.raw.neversoft_bootstrap);
             ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (SYMLINKS_FILE.equals(name)) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(zip, StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.split("←", 2);
                        if (parts.length != 2) throw new IllegalStateException("Malformed bootstrap symlink: " + line);
                        String target = parts[0];
                        if (target.startsWith(STOCK_PREFIX)) {
                            target = prefix.getAbsolutePath() + target.substring(STOCK_PREFIX.length());
                        }
                        File link = safeChild(staging, parts[1]);
                        File parent = link.getParentFile();
                        if (parent != null && !parent.exists() && !parent.mkdirs()) {
                            throw new IllegalStateException("Cannot create symlink parent: " + parent);
                        }
                        symlinks.add(new Symlink(target, link));
                    }
                    continue;
                }

                File target = safeChild(staging, name);
                if (entry.isDirectory()) {
                    if (!target.exists() && !target.mkdirs()) {
                        throw new IllegalStateException("Cannot create bootstrap directory: " + target);
                    }
                    continue;
                }

                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IllegalStateException("Cannot create bootstrap parent: " + parent);
                }
                try (FileOutputStream output = new FileOutputStream(target)) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) output.write(buffer, 0, read);
                }

                if (shouldBeExecutable(name)) Os.chmod(target.getAbsolutePath(), 0700);
            }
        } catch (Throwable t) {
            deleteRecursively(staging);
            throw t;
        }

        if (symlinks.isEmpty()) {
            deleteRecursively(staging);
            throw new IllegalStateException("Bootstrap does not contain " + SYMLINKS_FILE);
        }
        for (Symlink symlink : symlinks) {
            try {
                Os.symlink(symlink.target, symlink.link.getAbsolutePath());
            } catch (ErrnoException e) {
                Log.w(TAG, "Unable to create bootstrap symlink " + symlink.link + " -> " + symlink.target, e);
            }
        }

        deleteRecursively(prefix);
        if (!staging.renameTo(prefix)) {
            deleteRecursively(staging);
            throw new IllegalStateException("Cannot activate NeverSoft prefix");
        }

        File home = new File(rootfs, "home");
        File tmp = new File(prefix, "tmp");
        home.mkdirs();
        tmp.mkdirs();

        fixRelocatedBootstrap(prefix);
    }

    /**
     * Redirect apt/dpkg state to NeverSoft's physical prefix. The bootstrap's
     * packages remain signed by the upstream Termux repository; HTTP is used for
     * the initial bootstrap compatibility download because some base TLS libs
     * still look for certificates at the historical Termux path before proot is
     * available.
     */
    private static void fixRelocatedBootstrap(File prefix) throws Exception {
        String p = prefix.getAbsolutePath();
        File aptDir = new File(prefix, "etc/apt");
        aptDir.mkdirs();
        File aptConf = new File(aptDir, "apt.conf");
        String conf =
            "Dir \"/\";\n" +
            "Dir::State \"" + p + "/var/lib/apt/\";\n" +
            "Dir::State::status \"" + p + "/var/lib/dpkg/status\";\n" +
            "Dir::Cache \"" + p + "/var/cache/apt/\";\n" +
            "Dir::Log \"" + p + "/var/log/apt/\";\n" +
            "Dir::Etc \"" + p + "/etc/apt/\";\n" +
            "Dir::Etc::SourceList \"" + p + "/etc/apt/sources.list\";\n" +
            "Dir::Etc::SourceParts \"" + p + "/etc/apt/sources.list.d\";\n" +
            "Dir::Bin::dpkg \"" + p + "/bin/dpkg\";\n" +
            "Dir::Bin::Methods \"" + p + "/lib/apt/methods/\";\n" +
            "Dpkg::Options:: \"--force-configure-any\";\n" +
            "Dpkg::Options:: \"--force-bad-path\";\n" +
            "Dpkg::Options:: \"--instdir=" + p + "\";\n";
        writeText(aptConf, conf);

        new File(prefix, "var/log/apt").mkdirs();
        new File(prefix, "var/lib/dpkg/info").mkdirs();
        new File(prefix, "var/lib/dpkg/updates").mkdirs();
        new File(prefix, "var/lib/dpkg/triggers").mkdirs();
        new File(prefix, "var/cache/apt/archives/partial").mkdirs();
        new File(prefix, "var/lib/apt/lists/partial").mkdirs();

        File sources = new File(aptDir, "sources.list");
        if (sources.isFile()) {
            String text = readText(sources);
            // Initial compatibility setup may run before the stock certificate
            // path is available. APT package signatures still verify integrity.
            writeText(sources, text.replace("https://", "http://"));
        }

        File status = new File(prefix, "var/lib/dpkg/status");
        if (status.isFile()) writeText(status, readText(status).replace(STOCK_PREFIX, p));
    }

    private static void installNeverSoftTools(File prefix) throws Exception {
        File bin = new File(prefix, "bin");
        if (!bin.isDirectory() && !bin.mkdirs()) throw new IllegalStateException("Cannot create " + bin);

        String bash = new File(bin, "bash").getAbsolutePath();
        File compat = new File(bin, "neversoft-shell");
        String compatScript = "#!" + bash + "\n" +
            "set -e\n" +
            "P=${PREFIX:-" + prefix.getAbsolutePath() + "}\n" +
            "H=${HOME:-" + prefix.getParentFile().getAbsolutePath() + "/home}\n" +
            "PROOT=\"$P/bin/proot\"\n" +
            "if [ ! -x \"$PROOT\" ]; then exec \"$P/bin/bash\" -lc \"$*\"; fi\n" +
            "exec \"$PROOT\" -0 -b \"$P:" + ShellRuntime.STOCK_PREFIX + "\" -b \"$H:" + ShellRuntime.STOCK_HOME + "\" -b /dev -b /proc -b /sys -w \"$H\" \"$P/bin/bash\" -lc \"$*\"\n";
        writeExecutable(compat, compatScript);

        File pkg = new File(bin, "pkg");
        String pkgScript = "#!" + bash + "\n" +
            "set -e\n" +
            "cmd=${1:-help}\n" +
            "if [ $# -gt 0 ]; then shift; fi\n" +
            "runner=\"" + compat.getAbsolutePath() + "\"\n" +
            "case \"$cmd\" in\n" +
            "  install|in) exec \"$runner\" \"apt-get install -y $*\" ;;\n" +
            "  remove|uninstall) exec \"$runner\" \"apt-get remove -y $*\" ;;\n" +
            "  update|up) exec \"$runner\" \"apt-get update\" ;;\n" +
            "  upgrade) exec \"$runner\" \"apt-get update && apt-get full-upgrade -y\" ;;\n" +
            "  search) exec \"$runner\" \"apt-cache search $*\" ;;\n" +
            "  show) exec \"$runner\" \"apt-cache show $*\" ;;\n" +
            "  help|-h|--help) echo 'NeverSoft pkg: install remove update upgrade search show' ;;\n" +
            "  *) echo \"Unknown pkg command: $cmd\" >&2; exit 2 ;;\n" +
            "esac\n";
        writeExecutable(pkg, pkgScript);
    }

    static void setupSharedStorage(Context context) throws Exception {
        File home = new File(context.getFilesDir(), "home");
        File storage = new File(home, "storage");
        if (!storage.isDirectory() && !storage.mkdirs()) {
            throw new IllegalStateException("Cannot create storage directory: " + storage);
        }

        String[][] links = new String[][] {
            {"shared", SHARED_STORAGE},
            {"downloads", SHARED_STORAGE + "/Download"},
            {"documents", SHARED_STORAGE + "/Documents"},
            {"dcim", SHARED_STORAGE + "/DCIM"},
            {"pictures", SHARED_STORAGE + "/Pictures"},
            {"movies", SHARED_STORAGE + "/Movies"},
            {"music", SHARED_STORAGE + "/Music"}
        };
        for (String[] entry : links) {
            File link = new File(storage, entry[0]);
            try {
                Os.unlink(link.getAbsolutePath());
            } catch (ErrnoException ignored) {
                if (link.exists() && link.isDirectory()) continue;
            }
            Os.symlink(entry[1], link.getAbsolutePath());
        }
    }

    static String[] shellEnvironment(Context context) {
        File rootfs = context.getFilesDir();
        File prefix = new File(rootfs, "usr");
        File home = new File(rootfs, "home");
        File tmp = new File(prefix, "tmp");
        String p = prefix.getAbsolutePath();
        return new String[] {
            "HOME=" + home.getAbsolutePath(),
            "PREFIX=" + p,
            "TMPDIR=" + tmp.getAbsolutePath(),
            "TMP=" + tmp.getAbsolutePath(),
            "TEMP=" + tmp.getAbsolutePath(),
            "PATH=" + p + "/bin:" + p + "/bin/applets:/system/bin:/system/xbin",
            "SHELL=" + p + "/bin/bash",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=en_US.UTF-8",
            "EXTERNAL_STORAGE=" + SHARED_STORAGE,
            "LD_LIBRARY_PATH=" + p + "/lib",
            "LD_PRELOAD=" + p + "/lib/libtermux-exec.so",
            "PROOT_TMP_DIR=" + tmp.getAbsolutePath(),
            "APT_CONFIG=" + p + "/etc/apt/apt.conf",
            "DPKG_ADMINDIR=" + p + "/var/lib/dpkg",
            "SSL_CERT_FILE=" + p + "/etc/tls/cert.pem",
            "SSL_CERT_DIR=/system/etc/security/cacerts",
            "CURL_CA_BUNDLE=" + p + "/etc/tls/cert.pem",
            "GIT_SSL_CAINFO=" + p + "/etc/tls/cert.pem",
            "GIT_EXEC_PATH=" + p + "/libexec/git-core",
            "TERMUX_PREFIX=" + p,
            "TERMUX__PREFIX=" + p,
            "TERMUX__HOME=" + home.getAbsolutePath(),
            "TERMUX_APP__PACKAGE_NAME=" + context.getPackageName(),
            "TERMUX_APP__DATA_DIR=" + context.getApplicationInfo().dataDir
        };
    }

    private static boolean shouldBeExecutable(String name) {
        return name.startsWith("bin/") || name.startsWith("libexec/") ||
            name.startsWith("lib/apt/methods/") || name.startsWith("lib/bash/") ||
            name.endsWith(".so") || name.contains("/bin/");
    }

    private static File safeChild(File root, String relative) throws Exception {
        File file = new File(root, relative);
        String rootPath = root.getCanonicalPath() + File.separator;
        String filePath = file.getCanonicalPath();
        if (!filePath.startsWith(rootPath)) throw new SecurityException("Bootstrap path escapes prefix: " + relative);
        return file;
    }

    private static String readText(File file) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
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

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static final class Symlink {
        final String target;
        final File link;
        Symlink(String target, File link) {
            this.target = target;
            this.link = link;
        }
    }
}
