package com.neversoft.shell;

import android.content.Context;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;

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

final class BootstrapInstaller {
    interface Callback {
        void onReady();
        void onError(String message, Throwable error);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final String SYMLINKS_FILE = "SYMLINKS.txt";
    private static final String SHARED_STORAGE = "/storage/emulated/0";

    private BootstrapInstaller() {}

    static void ensureInstalled(Context context, Callback callback) {
        final Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                File rootfs = app.getFilesDir();
                File prefix = new File(rootfs, "usr");
                File bash = new File(prefix, "bin/bash");
                if (!bash.isFile()) install(app, rootfs, prefix);
                installNeverSoftTools(prefix);
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
                        File link = safeChild(staging, parts[1]);
                        File parent = link.getParentFile();
                        if (parent != null && !parent.exists() && !parent.mkdirs()) {
                            throw new IllegalStateException("Cannot create symlink parent: " + parent);
                        }
                        symlinks.add(new Symlink(parts[0], link));
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

                if (name.startsWith("bin/") || name.startsWith("libexec/") ||
                    name.startsWith("lib/apt/apt-helper") || name.startsWith("lib/apt/methods/") ||
                    name.equals("etc/termux/bootstrap/termux-bootstrap-second-stage.sh")) {
                    Os.chmod(target.getAbsolutePath(), 0700);
                }
            }
        } catch (Throwable t) {
            deleteRecursively(staging);
            throw t;
        }

        if (symlinks.isEmpty()) {
            deleteRecursively(staging);
            throw new IllegalStateException("Bootstrap does not contain " + SYMLINKS_FILE);
        }
        for (Symlink symlink : symlinks) Os.symlink(symlink.target, symlink.link.getAbsolutePath());

        deleteRecursively(prefix);
        if (!staging.renameTo(prefix)) {
            deleteRecursively(staging);
            throw new IllegalStateException("Cannot activate NeverSoft prefix");
        }

        File home = new File(rootfs, "home");
        File tmp = new File(prefix, "tmp");
        home.mkdirs();
        tmp.mkdirs();

        File secondStage = new File(prefix, "etc/termux/bootstrap/termux-bootstrap-second-stage.sh");
        if (secondStage.isFile()) {
            int exit = runSecondStage(context, rootfs, prefix, home, tmp, secondStage);
            if (exit != 0) {
                deleteRecursively(prefix);
                throw new IllegalStateException("NeverSoft bootstrap second stage failed with exit " + exit);
            }
        }
    }

    /**
     * NeverSoft deliberately does not ship Termux's termux-tools/termux-am stack.
     * This lightweight pkg command keeps the familiar workflow while routing
     * directly to our native apt installation and our own repository.
     */
    private static void installNeverSoftTools(File prefix) throws Exception {
        File bin = new File(prefix, "bin");
        if (!bin.isDirectory() && !bin.mkdirs()) throw new IllegalStateException("Cannot create " + bin);
        File pkg = new File(bin, "pkg");
        String script = "#!" + new File(bin, "bash").getAbsolutePath() + "\n" +
            "set -e\n" +
            "cmd=${1:-help}\n" +
            "if [ $# -gt 0 ]; then shift; fi\n" +
            "case \"$cmd\" in\n" +
            "  install|in) exec apt install \"$@\" ;;\n" +
            "  remove|uninstall) exec apt remove \"$@\" ;;\n" +
            "  reinstall) exec apt reinstall \"$@\" ;;\n" +
            "  update|up) exec apt update \"$@\" ;;\n" +
            "  upgrade) apt update && exec apt full-upgrade \"$@\" ;;\n" +
            "  search) exec apt search \"$@\" ;;\n" +
            "  show) exec apt show \"$@\" ;;\n" +
            "  list-all|list) exec apt list \"$@\" ;;\n" +
            "  clean) exec apt clean \"$@\" ;;\n" +
            "  autoclean) exec apt autoclean \"$@\" ;;\n" +
            "  help|-h|--help)\n" +
            "    echo 'NeverSoft pkg: install remove reinstall update upgrade search show list-all clean autoclean' ;;\n" +
            "  *) echo \"Unknown pkg command: $cmd\" >&2; exit 2 ;;\n" +
            "esac\n";
        try (FileOutputStream out = new FileOutputStream(pkg, false)) {
            out.write(script.getBytes(StandardCharsets.UTF_8));
        }
        Os.chmod(pkg.getAbsolutePath(), 0700);
    }

    /**
     * Create Termux-style convenience links into Android shared storage. The app
     * calls this after the runtime storage permission is granted. Existing real
     * directories are never destroyed; only existing symlinks/files are replaced.
     */
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

    private static int runSecondStage(Context context, File rootfs, File prefix, File home, File tmp, File script) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(script.getAbsolutePath());
        pb.directory(rootfs);
        pb.redirectErrorStream(true);
        configureEnvironment(pb, context, rootfs, prefix, home, tmp);
        java.lang.Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            while (reader.readLine() != null) { /* drain output so maintainer scripts cannot block */ }
        }
        return process.waitFor();
    }

    static void configureEnvironment(ProcessBuilder pb, Context context, File rootfs, File prefix, File home, File tmp) {
        String prefixPath = prefix.getAbsolutePath();
        String dataDir = context.getApplicationInfo().dataDir;
        pb.environment().clear();
        pb.environment().put("HOME", home.getAbsolutePath());
        pb.environment().put("PREFIX", prefixPath);
        pb.environment().put("TMPDIR", tmp.getAbsolutePath());
        pb.environment().put("PATH", prefixPath + "/bin:/system/bin:/system/xbin");
        pb.environment().put("SHELL", prefixPath + "/bin/bash");
        pb.environment().put("TERM", "xterm-256color");
        pb.environment().put("COLORTERM", "truecolor");
        pb.environment().put("LANG", "en_US.UTF-8");
        pb.environment().put("EXTERNAL_STORAGE", SHARED_STORAGE);
        pb.environment().put("LD_PRELOAD", prefixPath + "/lib/libtermux-exec.so");
        pb.environment().put("TERMUX__UID", Integer.toString(Process.myUid()));
        pb.environment().put("TERMUX__USER_ID", Integer.toString(Process.myUid()));
        pb.environment().put("TERMUX_APP__PACKAGE_NAME", context.getPackageName());
        pb.environment().put("TERMUX_APP__DATA_DIR", dataDir);
        pb.environment().put("TERMUX__ROOTFS", rootfs.getAbsolutePath());
        pb.environment().put("TERMUX__PREFIX", prefixPath);
        pb.environment().put("TERMUX__HOME", home.getAbsolutePath());
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
            "PATH=" + p + "/bin:/system/bin:/system/xbin",
            "SHELL=" + p + "/bin/bash",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=en_US.UTF-8",
            "EXTERNAL_STORAGE=" + SHARED_STORAGE,
            "LD_PRELOAD=" + p + "/lib/libtermux-exec.so",
            "TERMUX__UID=" + Process.myUid(),
            "TERMUX__USER_ID=" + Process.myUid(),
            "TERMUX_APP__PACKAGE_NAME=" + context.getPackageName(),
            "TERMUX_APP__DATA_DIR=" + context.getApplicationInfo().dataDir,
            "TERMUX__ROOTFS=" + rootfs.getAbsolutePath(),
            "TERMUX__PREFIX=" + p,
            "TERMUX__HOME=" + home.getAbsolutePath()
        };
    }

    private static File safeChild(File root, String relative) throws Exception {
        File file = new File(root, relative);
        String rootPath = root.getCanonicalPath() + File.separator;
        String filePath = file.getCanonicalPath();
        if (!filePath.startsWith(rootPath)) throw new SecurityException("Bootstrap path escapes prefix: " + relative);
        return file;
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
