package com.neversoft.shell.ai;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.neversoft.shell.ShellRuntime;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native multi-target OSINT console. All bundled searches work without API keys or accounts. */
public final class OsintDashboard {
    private static final int USERNAME = 1;
    private static final int EMAIL = 2;
    private static final int PHONE = 4;
    private static final int DOMAIN = 8;
    private static final int URL = 16;
    private static final int IP = 32;
    private static final int FILE = 64;

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    // A single queue prevents multiple providers from hammering the same public service.
    private final ExecutorService scans = Executors.newSingleThreadExecutor();
    private volatile long lastHeavyRequestAt;
    private final ExecutorService installer = Executors.newSingleThreadExecutor();
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final List<java.lang.Process> activeProcesses = Collections.synchronizedList(new ArrayList<>());
    private final List<Tool> tools = new ArrayList<>();

    private Dialog dialog;
    private EditText targets;
    private TextView toolStatus;
    private TextView output;
    private ScrollView outputScroll;
    private CheckBox deepWeb;
    private Button scanButton;
    private Button installButton;
    private final StringBuilder report = new StringBuilder();

    public OsintDashboard(Activity activity) {
        this.activity = activity;
        buildTools();
    }

    public void show() {
        dialog = new Dialog(activity, android.R.style.Theme_Material_NoActionBar);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.setBackgroundColor(Color.rgb(24, 24, 24));

        LinearLayout titleRow = new LinearLayout(activity);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("NeverSoft OSINT", 19, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView subtitle = label("NO API KEY · NO ACCOUNT", 10, Color.rgb(150, 190, 225));
        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.addView(title);
        heading.addView(subtitle);
        titleRow.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button close = button("Close");
        titleRow.addView(close, new LinearLayout.LayoutParams(dp(70), dp(38)));
        root.addView(titleRow);

        targets = new EditText(activity);
        targets.setHint("Targets — one per line\nusername\nemail@example.com\n+15551234567\nexample.com\nhttps://example.com\n/storage/emulated/0/Download/photo.jpg");
        targets.setHintTextColor(Color.rgb(130, 130, 130));
        targets.setTextColor(Color.WHITE);
        targets.setTextSize(13);
        targets.setGravity(Gravity.TOP | Gravity.START);
        targets.setMinLines(5);
        targets.setMaxLines(9);
        targets.setPadding(dp(10), dp(8), dp(10), dp(8));
        targets.setBackgroundColor(Color.rgb(40, 40, 40));
        LinearLayout.LayoutParams targetLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        targetLp.setMargins(0, dp(10), 0, dp(8));
        root.addView(targets, targetLp);

        deepWeb = new CheckBox(activity);
        deepWeb.setText("Deep web discovery (Katana / hakrawler / gospider / subjs)");
        deepWeb.setTextColor(Color.rgb(210, 210, 210));
        deepWeb.setChecked(false);
        root.addView(deepWeb);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        installButton = button("Install pack");
        scanButton = button("Run all");
        Button stop = button("Stop");
        Button export = button("Export");
        actions.addView(installButton, new LinearLayout.LayoutParams(0, dp(42), 1f));
        actions.addView(scanButton, new LinearLayout.LayoutParams(0, dp(42), 1f));
        actions.addView(stop, new LinearLayout.LayoutParams(0, dp(42), 0.75f));
        actions.addView(export, new LinearLayout.LayoutParams(0, dp(42), 0.85f));
        root.addView(actions);

        toolStatus = label("Checking installed tools…", 11, Color.rgb(170, 170, 170));
        toolStatus.setPadding(0, dp(7), 0, dp(5));
        root.addView(toolStatus);

        output = label("Ready. Targets are auto-detected as username, email, phone, domain, URL, IP, or local file.\n", 11, Color.rgb(225, 225, 225));
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        outputScroll = new ScrollView(activity);
        outputScroll.addView(output, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(outputScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        close.setOnClickListener(v -> dialog.dismiss());
        installButton.setOnClickListener(v -> installPack());
        scanButton.setOnClickListener(v -> runAll());
        stop.setOnClickListener(v -> stopAll());
        export.setOnClickListener(v -> exportReport());

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.setOnDismissListener(d -> stopAll());
        dialog.show();
        if (window != null) window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        refreshToolStatus();
    }

    private void buildTools() {
        tools.add(new Tool("maigret", "Maigret", USERNAME, "command -v maigret", false));
        tools.add(new Tool("sherlock", "Sherlock", USERNAME, "command -v sherlock", false));
        tools.add(new Tool("blackbird", "Blackbird", USERNAME | EMAIL, "test -f \"$HOME/tools/blackbird/blackbird.py\"", false));
        tools.add(new Tool("holehe", "Holehe", EMAIL, "command -v holehe", false));
        tools.add(new Tool("socialscan", "Socialscan", USERNAME | EMAIL, "command -v socialscan", false));
        tools.add(new Tool("phoneinfoga", "PhoneInfoga", PHONE, "test -x \"$HOME/go/bin/phoneinfoga\"", false));
        tools.add(new Tool("whois", "WHOIS", DOMAIN | IP, "command -v whois", false));
        tools.add(new Tool("dig", "DNS dig", DOMAIN | IP, "command -v dig", false));
        tools.add(new Tool("assetfinder", "Assetfinder", DOMAIN, "test -x \"$HOME/go/bin/assetfinder\"", false));
        tools.add(new Tool("subfinder", "Subfinder keyless sources", DOMAIN, "test -x \"$HOME/go/bin/subfinder\"", false));
        tools.add(new Tool("dnsx", "dnsx", DOMAIN, "test -x \"$HOME/go/bin/dnsx\"", false));
        tools.add(new Tool("waybackurls", "Waybackurls", DOMAIN | URL, "test -x \"$HOME/go/bin/waybackurls\"", false));
        tools.add(new Tool("gau", "gau", DOMAIN | URL, "test -x \"$HOME/go/bin/gau\"", false));
        tools.add(new Tool("httpx", "httpx", DOMAIN | URL, "test -x \"$HOME/go/bin/httpx\"", false));
        tools.add(new Tool("unfurl", "unfurl", URL, "test -x \"$HOME/go/bin/unfurl\"", false));
        tools.add(new Tool("katana", "Katana", DOMAIN | URL, "test -x \"$HOME/go/bin/katana\"", true));
        tools.add(new Tool("hakrawler", "hakrawler", DOMAIN | URL, "test -x \"$HOME/go/bin/hakrawler\"", true));
        tools.add(new Tool("gospider", "gospider", DOMAIN | URL, "test -x \"$HOME/go/bin/gospider\"", true));
        tools.add(new Tool("subjs", "subjs", DOMAIN | URL, "test -x \"$HOME/go/bin/subjs\"", true));
        tools.add(new Tool("exiftool", "ExifTool", FILE, "command -v exiftool", false));
        tools.add(new Tool("file", "file", FILE, "command -v file", false));
        tools.add(new Tool("strings", "strings", FILE, "command -v strings", false));
    }

    private void refreshToolStatus() {
        installer.execute(() -> {
            List<String> installed = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (Tool tool : tools) {
                CmdResult r = runCommand(tool.probe, 8);
                (r.exitCode == 0 ? installed : missing).add(tool.label);
            }
            main.post(() -> toolStatus.setText("Installed " + installed.size() + "/" + tools.size() + " · " + join(installed) +
                (missing.isEmpty() ? "" : "\nMissing · " + join(missing))));
        });
    }

    private void installPack() {
        installButton.setEnabled(false);
        append("\n=== INSTALL NO-KEY OSINT PACK ===\n");
        installer.execute(() -> {
            String[] steps = new String[] {
                "pkg update",
                "pkg install git curl wget jq unzip tar file python perl golang dnsutils whois binutils",
                "pkg install exiftool",
                "mkdir -p \"$HOME/go/bin\" \"$HOME/tools\"",
                "go install github.com/tomnomnom/waybackurls@latest",
                "go install github.com/tomnomnom/assetfinder@latest",
                "go install github.com/tomnomnom/unfurl@latest",
                "go install github.com/lc/gau/v2/cmd/gau@latest",
                "go install github.com/projectdiscovery/httpx/cmd/httpx@latest",
                "go install github.com/projectdiscovery/dnsx/cmd/dnsx@latest",
                "go install github.com/projectdiscovery/subfinder/v2/cmd/subfinder@latest",
                "go install github.com/projectdiscovery/katana/cmd/katana@latest",
                "go install github.com/hakluke/hakrawler@latest",
                "go install github.com/jaeles-project/gospider@latest",
                "go install github.com/lc/subjs@latest",
                "go install github.com/sundowndev/phoneinfoga/v2@latest",
                "pip install holehe",
                "pip install socialscan",
                "pip install maigret",
                "if [ ! -d \"$HOME/tools/blackbird/.git\" ]; then git clone --depth 1 https://github.com/p1ngul1n0/blackbird.git \"$HOME/tools/blackbird\"; fi",
                "cd \"$HOME/tools/blackbird\" && pip install -r requirements.txt"
            };
            for (String step : steps) {
                if (stopRequested.get()) break;
                append("\n$ " + step + "\n");
                CmdResult r = runCommand(step, step.startsWith("go install") || step.contains("pip install") ? 300 : 180);
                append(trim(r.output, 12000) + "\n[exit " + r.exitCode + "]\n");
            }
            append("\nSherlock is detected and usable if already installed, but auto-install is skipped on Python 3.14 because its pandas dependency can trigger a long source build.\n");
            main.post(() -> installButton.setEnabled(true));
            refreshToolStatus();
        });
    }

    private void runAll() {
        Set<String> unique = parseTargets(targets.getText().toString());
        if (unique.isEmpty()) {
            append("\n[enter at least one target]\n");
            return;
        }
        stopRequested.set(false);
        scanButton.setEnabled(false);
        report.setLength(0);
        report.append("NeverSoft OSINT report\n").append(new Date()).append("\n\n");
        append("\n=== SCAN " + unique.size() + " TARGET(S) ===\n");

        installer.execute(() -> {
            final List<Tool> installed = new ArrayList<>();
            for (Tool tool : tools) if (runCommand(tool.probe, 6).exitCode == 0) installed.add(tool);
            int taskCount = 0;
            for (String target : unique) {
                int type = classify(target);
                append("\n## " + target + " [" + typeName(type) + "]\n");
                report.append("\n## ").append(target).append(" [").append(typeName(type)).append("]\n");
                for (Tool tool : installed) {
                    if ((tool.mask & type) == 0) continue;
                    if (tool.deep && !deepWeb.isChecked()) continue;
                    String command = commandFor(tool.id, target, type);
                    if (command == null || command.isEmpty()) continue;
                    taskCount++;
                    scans.execute(() -> runOne(tool, target, command));
                }
            }
            if (taskCount == 0) append("\n[No compatible installed tools. Tap Install pack.]\n");
            final int submitted = taskCount;
            scans.execute(() -> {
                // Marker only. The serialized request queue preserves provider pacing, so UI remains usable while jobs finish.
                try { Thread.sleep(Math.min(1500L, 200L * Math.max(1, submitted))); } catch (InterruptedException ignored) {}
                main.post(() -> scanButton.setEnabled(true));
            });
        });
    }

    private void runOne(Tool tool, String target, String command) {
        if (stopRequested.get()) return;
        append("\n[" + tool.label + "] " + target + "\n");
        CmdResult r = null;
        for (int attempt = 0; attempt < 4 && !stopRequested.get(); attempt++) {
            paceHeavySearch();
            r = runCommand(command, tool.deep ? 120 : 90);
            if (!isRateLimited(r)) break;
            long wait = retryAfterMillis(r.output, attempt);
            append("[rate limited; retrying " + tool.label + " in " + (wait / 1000L) + "s]\n");
            sleepInterruptibly(wait);
        }
        if (r == null) r = new CmdResult(130, "stopped");
        String text = trim(r.output, 16000);
        if (text.isEmpty()) text = "(no output)";
        String block = text + "\n[exit " + r.exitCode + "]\n";
        append(block);
        synchronized (report) {
            report.append("\n[").append(tool.label).append("]\n").append(block);
        }
    }

    /** Balanced mode: configurable 60-120 second spacing between public-provider searches. */
    private void paceHeavySearch() {
        int configured = activity.getSharedPreferences("neversoft_osint", Activity.MODE_PRIVATE)
            .getInt("balanced_delay_seconds", 60);
        long base = Math.max(60, Math.min(120, configured)) * 1000L;
        long jitter = (long)(Math.random() * Math.min(60_000L, base / 2L));
        long wait = lastHeavyRequestAt + base + jitter - System.currentTimeMillis();
        if (wait > 0) {
            append("[Balanced pacing: " + ((wait + 999L) / 1000L) + "s]\n");
            sleepInterruptibly(wait);
        }
        lastHeavyRequestAt = System.currentTimeMillis();
    }

    private boolean isRateLimited(CmdResult result) {
        if (result == null) return false;
        String value = result.output.toLowerCase(Locale.US);
        return result.exitCode == 429 || value.contains("429") || value.contains("rate limit")
            || value.contains("too many requests") || value.contains("retry-after");
    }

    private long retryAfterMillis(String output, int attempt) {
        if (output != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)retry-after[^0-9]{0,8}([0-9]{1,4})").matcher(output);
            if (m.find()) {
                try { return Math.max(1L, Math.min(1800L, Long.parseLong(m.group(1)))) * 1000L; }
                catch (NumberFormatException ignored) {}
            }
        }
        long exponential = 15_000L * (1L << Math.min(5, attempt));
        return Math.min(300_000L, exponential) + (long)(Math.random() * 5_000L);
    }

    private void sleepInterruptibly(long millis) {
        long end = System.currentTimeMillis() + millis;
        while (!stopRequested.get() && System.currentTimeMillis() < end) {
            try { Thread.sleep(Math.min(1000L, end - System.currentTimeMillis())); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private String commandFor(String id, String target, int type) {
        String q = shQuote(target);
        String domain = domainFrom(target);
        String dq = shQuote(domain);
        String url = target.startsWith("http://") || target.startsWith("https://") ? target : "https://" + domain;
        String uq = shQuote(url);
        switch (id) {
            case "maigret": return "maigret " + q;
            case "sherlock": return "sherlock " + q + " --print-found";
            case "blackbird": return "python \"$HOME/tools/blackbird/blackbird.py\" " + (type == EMAIL ? "--email " : "--username ") + q;
            case "holehe": return "holehe " + q;
            case "socialscan": return "socialscan " + q + " --show-urls";
            case "phoneinfoga": return "\"$HOME/go/bin/phoneinfoga\" scan -n " + q;
            case "whois": return "whois " + q;
            case "dig": return type == IP ? "dig +short -x " + q : "for t in A AAAA MX NS TXT CNAME; do echo \"--- $t ---\"; dig +short $t " + dq + "; done";
            case "assetfinder": return "\"$HOME/go/bin/assetfinder\" --subs-only " + dq;
            case "subfinder": return "\"$HOME/go/bin/subfinder\" -d " + dq + " -silent";
            case "dnsx": return "printf '%s\\n' " + dq + " | \"$HOME/go/bin/dnsx\" -silent -a -aaaa -cname -mx -ns -txt";
            case "waybackurls": return "printf '%s\\n' " + dq + " | \"$HOME/go/bin/waybackurls\"";
            case "gau": return "\"$HOME/go/bin/gau\" --subs " + dq;
            case "httpx": return "printf '%s\\n' " + uq + " | \"$HOME/go/bin/httpx\" -silent -status-code -title -tech-detect -follow-redirects";
            case "unfurl": return "printf '%s\\n' " + q + " | \"$HOME/go/bin/unfurl\" domains; printf '%s\\n' " + q + " | \"$HOME/go/bin/unfurl\" paths; printf '%s\\n' " + q + " | \"$HOME/go/bin/unfurl\" keys";
            case "katana": return "\"$HOME/go/bin/katana\" -u " + uq + " -silent -d 2";
            case "hakrawler": return "printf '%s\\n' " + uq + " | \"$HOME/go/bin/hakrawler\" -plain -depth 2";
            case "gospider": return "\"$HOME/go/bin/gospider\" -s " + uq + " -q -d 1";
            case "subjs": return "printf '%s\\n' " + uq + " | \"$HOME/go/bin/subjs\"";
            case "exiftool": return "exiftool " + q;
            case "file": return "file -b " + q;
            case "strings": return "strings " + q + " | head -n 250";
            default: return null;
        }
    }

    private void stopAll() {
        stopRequested.set(true);
        synchronized (activeProcesses) {
            for (java.lang.Process p : activeProcesses) {
                try { p.destroy(); } catch (Throwable ignored) {}
                try { p.destroyForcibly(); } catch (Throwable ignored) {}
            }
            activeProcesses.clear();
        }
        if (scanButton != null) scanButton.setEnabled(true);
        append("\n[stop requested]\n");
    }

    private CmdResult runCommand(String command, int timeoutSeconds) {
        java.lang.Process process = null;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            process = ShellRuntime.processBuilder(activity, command).start();
            activeProcesses.add(process);
            final java.lang.Process p = process;
            Thread reader = new Thread(() -> {
                try (BufferedInputStream in = new BufferedInputStream(p.getInputStream())) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        if (bytes.size() < 64 * 1024) bytes.write(buf, 0, Math.min(n, 64 * 1024 - bytes.size()));
                    }
                } catch (Exception ignored) {}
            }, "NeverSoft-osint-reader");
            reader.setDaemon(true);
            reader.start();
            boolean done = process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            if (!done) {
                process.destroy();
                process.destroyForcibly();
                reader.join(600);
                return new CmdResult(124, new String(bytes.toByteArray(), StandardCharsets.UTF_8) + "\n[timed out]");
            }
            reader.join(800);
            return new CmdResult(process.exitValue(), new String(bytes.toByteArray(), StandardCharsets.UTF_8).trim());
        } catch (Throwable t) {
            return new CmdResult(125, t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        } finally {
            if (process != null) activeProcesses.remove(process);
        }
    }

    private void exportReport() {
        installer.execute(() -> {
            try {
                File downloads = new File(ShellRuntime.home(activity), "storage/downloads");
                if (!downloads.isDirectory()) downloads = new File(ShellRuntime.home(activity), "reports");
                downloads.mkdirs();
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
                File dest = new File(downloads, "NeverSoft-OSINT-" + stamp + ".txt");
                String data;
                synchronized (report) { data = report.length() == 0 ? output.getText().toString() : report.toString(); }
                try (FileOutputStream out = new FileOutputStream(dest)) { out.write(data.getBytes(StandardCharsets.UTF_8)); }
                append("\n[exported " + dest.getAbsolutePath() + "]\n");
            } catch (Throwable t) {
                append("\n[export failed: " + t.getMessage() + "]\n");
            }
        });
    }

    private Set<String> parseTargets(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) return out;
        for (String line : raw.split("\\r?\\n")) {
            String value = line.trim();
            if (!value.isEmpty() && !value.startsWith("#")) out.add(value);
        }
        return out;
    }

    private int classify(String value) {
        String v = value.trim();
        if (v.startsWith("/") || v.startsWith("~/")) return FILE;
        if (v.matches("(?i)^[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}$")) return EMAIL;
        if (v.matches("^\\+?[0-9() .-]{7,24}$")) return PHONE;
        if (v.matches("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")) return IP;
        if (v.startsWith("http://") || v.startsWith("https://")) return URL;
        if (v.matches("(?i)^(?:[a-z0-9-]+\\.)+[a-z]{2,63}$")) return DOMAIN;
        return USERNAME;
    }

    private String domainFrom(String value) {
        String v = value.trim();
        v = v.replaceFirst("(?i)^https?://", "");
        int slash = v.indexOf('/');
        if (slash >= 0) v = v.substring(0, slash);
        int colon = v.indexOf(':');
        if (colon >= 0) v = v.substring(0, colon);
        return v;
    }

    private String typeName(int type) {
        switch (type) {
            case EMAIL: return "email";
            case PHONE: return "phone";
            case DOMAIN: return "domain";
            case URL: return "url";
            case IP: return "ip";
            case FILE: return "file";
            default: return "username";
        }
    }

    private void append(String value) {
        main.post(() -> {
            if (dialog == null || !dialog.isShowing()) return;
            output.append(value);
            outputScroll.post(() -> outputScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private TextView label(String value, int sp, int color) {
        TextView v = new TextView(activity);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private Button button(String text) {
        Button v = new Button(activity);
        v.setText(text);
        v.setTextSize(11);
        v.setTextColor(Color.WHITE);
        v.setAllCaps(false);
        v.setBackgroundColor(Color.rgb(24, 113, 190));
        v.setPadding(dp(4), 0, dp(4), 0);
        return v;
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private static String shQuote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private static String trim(String value, int max) { if (value == null) return ""; return value.length() <= max ? value : value.substring(0, max) + "\n…(truncated)"; }
    private static String join(List<String> values) { StringBuilder out = new StringBuilder(); for (String v : values) { if (out.length() > 0) out.append(", "); out.append(v); } return out.toString(); }

    private static final class Tool {
        final String id, label, probe;
        final int mask;
        final boolean deep;
        Tool(String id, String label, int mask, String probe, boolean deep) { this.id = id; this.label = label; this.mask = mask; this.probe = probe; this.deep = deep; }
    }

    private static final class CmdResult {
        final int exitCode;
        final String output;
        CmdResult(int exitCode, String output) { this.exitCode = exitCode; this.output = output == null ? "" : output; }
    }
}
