package com.neversoft.shell;

import android.content.Context;
import android.os.Process;
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

    private BootstrapInstaller() {}

    static void ensureInstalled(Context context, Callback callback) {
        final Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                File rootfs = app.getFilesDir();
                File prefix = new File(rootfs, "usr");
                File bash = new File(prefix, "bin/bash");
                if (!bash.isFile()) install(app, rootfs, prefix);
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
        pb.environment().put("LD_PRELOAD", prefixPath + "/lib/libtermux-exec.so");
        pb.environment().put("TERMUX__UID", Integer.toString(Process.myUid()));
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
            "LD_PRELOAD=" + p + "/lib/libtermux-exec.so",
            "TERMUX__UID=" + Process.myUid(),
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
