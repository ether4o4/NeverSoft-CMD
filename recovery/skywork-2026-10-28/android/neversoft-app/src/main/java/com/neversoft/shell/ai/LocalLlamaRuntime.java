package com.neversoft.shell.ai;

import android.content.Context;
import android.util.Log;
import com.neversoft.shell.ShellRuntime;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Long-lived, self-healing llama.cpp server for NeverSoft local AI. */
public final class LocalLlamaRuntime {
    public enum State {
        NO_MODEL, DOWNLOADING, MODEL_DOWNLOADED, STARTING_SERVER, SERVER_STARTING,
        SERVER_READY, GENERATING, SERVER_CRASHED, ERROR
    }
    public interface Listener { void onState(State state, String detail); }

    private static final String TAG = "NeverSoftLlama";
    public static final int PORT = 18080;
    private final Context context;
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
    private volatile Process process;
    private volatile File model;
    private volatile State state = State.NO_MODEL;
    private volatile String lastFailure = "";
    private volatile Listener listener;
    private volatile boolean stopping;
    private volatile int consecutiveFailures;
    private volatile long nextRestartAt;

    public LocalLlamaRuntime(Context context) {
        this.context = context.getApplicationContext();
        monitor.scheduleWithFixedDelay(this::monitorOnce, 3, 3, TimeUnit.SECONDS);
    }

    public void setListener(Listener value) { listener = value; }
    public State getState() { return state; }
    public String getLastFailure() { return lastFailure; }
    public File getModel() { return model; }

    public synchronized void setModel(File value) {
        if (value != null && value.isFile() && value.length() > 0) {
            model = value;
            transition(State.MODEL_DOWNLOADED, value.getName());
        }
    }

    public synchronized boolean isRunning() {
        Process p = process;
        if (p == null) return false;
        try { p.exitValue(); return false; }
        catch (IllegalThreadStateException alive) { return true; }
    }

    /** /health is authoritative, including after Activity recreation loses the Process handle. */
    public boolean isReady() { return healthCheck(); }

    public synchronized void ensureInstalled() throws Exception {
        File server = new File(ShellRuntime.prefix(context), "bin/llama-server");
        if (server.isFile()) return;
        ShellRuntime.ensurePackages(context, "llama-cpp");
        if (!server.isFile()) throw new IllegalStateException("llama-cpp installed but llama-server is missing");
    }

    public synchronized void start(File value) throws Exception {
        if (value == null || !value.isFile() || value.length() == 0)
            throw new IllegalArgumentException("GGUF model file is missing");
        model = value;
        transition(State.MODEL_DOWNLOADED, value.getName());
        if (isReady()) { transition(State.SERVER_READY, value.getName()); return; }

        stopProcess();
        stopStaleServers();
        ensureInstalled();
        stopping = false;
        transition(State.STARTING_SERVER, "Preparing llama.cpp");
        String args = "llama-server -m " + shQuote(value.getAbsolutePath())
            + " --host 127.0.0.1 --port " + PORT
            + " --ctx-size 1024 --batch-size 64 --ubatch-size 32"
            + " --threads 4 --threads-batch 4 --n-gpu-layers 0 --parallel 1";
        appendLog("\n=== START " + timestamp() + " ===\nargs: " + args + "\n");
        ProcessBuilder pb = ShellRuntime.processBuilder(context, "exec " + args);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        process = p;
        transition(State.SERVER_STARTING, "Loading weights");
        startOutputCapture(p);
        startExitWatcher(p);

        long deadline = System.currentTimeMillis() + 120_000L;
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning()) throw crashException("llama-server exited during startup");
            if (healthCheck()) {
                consecutiveFailures = 0;
                nextRestartAt = 0L;
                transition(State.SERVER_READY, value.getName());
                return;
            }
            Thread.sleep(500L);
        }
        stopProcess();
        fail(State.ERROR, "llama-server did not become healthy within 120 seconds");
        throw new IllegalStateException(lastFailure);
    }

    /** Verify process + endpoint, restart if needed, and wait for health. */
    public synchronized void ensureReady() throws Exception {
        if (isReady()) return;
        File current = model;
        if (current == null) throw new IllegalStateException("No downloaded model is selected");
        transition(State.STARTING_SERVER, lastFailure.isEmpty() ? "Starting inference server" : "Restarting");
        start(current);
        if (!isReady()) throw new IllegalStateException("Inference server restart completed without a healthy endpoint");
    }

    public synchronized void stop() {
        stopping = true;
        stopProcess();
        monitor.shutdownNow();
    }

    private void stopProcess() {
        Process p = process;
        process = null;
        if (p != null) {
            try { p.destroy(); } catch (Throwable ignored) {}
            try { if (!p.waitFor(500, TimeUnit.MILLISECONDS)) p.destroyForcibly(); }
            catch (Throwable ignored) {}
        }
    }

    private void startOutputCapture(Process p) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Log.d(TAG, line);
                    appendLog(line + "\n");
                }
            } catch (Throwable e) { appendLog("[output capture closed] " + reason(e) + "\n"); }
        }, "NeverSoft-llama-log");
        t.setDaemon(true);
        t.start();
    }

    private void startExitWatcher(Process p) {
        Thread t = new Thread(() -> {
            try {
                int code = p.waitFor();
                if (process == p) process = null;
                if (!stopping) {
                    int signal = code >= 128 ? code - 128 : 0;
                    String detail = "llama-server exited: code=" + code
                        + (signal > 0 ? ", signal=" + signal : "")
                        + "; log=" + logFile().getAbsolutePath();
                    appendLog(detail + "\n");
                    scheduleBackoff();
                    fail(State.SERVER_CRASHED, detail);
                }
            } catch (Throwable e) { if (!stopping) fail(State.SERVER_CRASHED, reason(e)); }
        }, "NeverSoft-llama-exit");
        t.setDaemon(true);
        t.start();
    }

    private void monitorOnce() {
        try {
            if (stopping || model == null || state == State.GENERATING || state == State.STARTING_SERVER
                    || state == State.SERVER_STARTING || System.currentTimeMillis() < nextRestartAt) return;
            if (!isReady()) {
                fail(State.SERVER_CRASHED, "Inference server stopped responding");
                ensureReady();
                transition(State.SERVER_READY, "Recovered");
            }
        } catch (Throwable e) {
            scheduleBackoff();
            fail(State.SERVER_CRASHED, "Recovery failed: " + reason(e));
        }
    }

    private void stopStaleServers() {
        try {
            Process cleanup = ShellRuntime.processBuilder(context,
                "pkill -TERM -f '[l]lama-server' >/dev/null 2>&1 || true; sleep 1").start();
            cleanup.waitFor(3, TimeUnit.SECONDS);
        } catch (Throwable e) {
            appendLog("[stale server cleanup] " + reason(e) + "\n");
        }
    }

    private void scheduleBackoff() {
        consecutiveFailures = Math.min(6, consecutiveFailures + 1);
        long delay = Math.min(60_000L, 3_000L * (1L << Math.min(4, consecutiveFailures - 1)));
        nextRestartAt = System.currentTimeMillis() + delay;
        appendLog("[recovery backoff] " + delay + "ms\n");
    }

    public boolean healthCheck() {
        try {
            HttpURLConnection c = (HttpURLConnection)new URL("http://127.0.0.1:" + PORT + "/health").openConnection();
            c.setConnectTimeout(1200); c.setReadTimeout(1200);
            int code = c.getResponseCode(); c.disconnect();
            return code >= 200 && code < 300;
        } catch (Throwable ignored) { return false; }
    }

    /** One automatic recovery/retry; conversation history remains owned by the UI. */
    public String chat(List<JSONObject> history, String systemPrompt) throws Exception {
        Exception first = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ensureReady();
                transition(State.GENERATING, "Generating…");
                String answer = request(history, systemPrompt);
                transition(State.SERVER_READY, attempt == 0 ? "Ready" : "Recovered");
                return answer;
            } catch (Exception e) {
                first = e;
                appendLog("[generation attempt " + (attempt + 1) + "] " + reason(e) + "\n");
                if (attempt == 0) {
                    fail(State.SERVER_CRASHED, reason(e));
                    stopProcess();
                }
            }
        }
        fail(State.ERROR, "Generation failed after recovery: " + reason(first));
        throw new IllegalStateException(lastFailure, first);
    }

    private String request(List<JSONObject> history, String systemPrompt) throws Exception {
        JSONObject payload = new JSONObject().put("model", "local-huggingface")
            .put("stream", false).put("temperature", 0.4);
        JSONArray messages = new JSONArray();
        if (systemPrompt != null && !systemPrompt.isEmpty())
            messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        if (history != null) for (JSONObject item : history) messages.put(item);
        payload.put("messages", messages);

        HttpURLConnection c = (HttpURLConnection)new URL("http://127.0.0.1:" + PORT + "/v1/chat/completions").openConnection();
        c.setConnectTimeout(10_000); c.setReadTimeout(180_000);
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = c.getOutputStream()) { out.write(body); }
        int code = c.getResponseCode();
        InputStream source = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        StringBuilder text = new StringBuilder();
        if (source != null) try (BufferedReader r = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8))) {
            String line; while ((line = r.readLine()) != null) text.append(line);
        }
        c.disconnect();
        if (code < 200 || code >= 300) throw new IllegalStateException("Local AI HTTP " + code + ": " + text);
        JSONArray choices = new JSONObject(text.toString()).optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new IllegalStateException("Local AI returned no choices");
        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
        if (message == null) throw new IllegalStateException("Local AI response is missing message");
        return message.optString("content", "").trim();
    }

    private void transition(State next, String detail) {
        state = next;
        Listener l = listener;
        if (l != null) l.onState(next, detail == null ? "" : detail);
    }
    private void fail(State next, String detail) {
        lastFailure = detail == null ? "Unknown native failure" : detail;
        appendLog("[" + next + "] " + lastFailure + "\n");
        transition(next, lastFailure);
    }
    private IllegalStateException crashException(String prefix) {
        return new IllegalStateException(prefix + ". " + (lastFailure.isEmpty() ? "See " + logFile() : lastFailure));
    }
    private File logFile() {
        File dir = new File(ShellRuntime.home(context), "logs");
        if (!dir.isDirectory()) dir.mkdirs();
        return new File(dir, "model.log");
    }
    private synchronized void appendLog(String value) {
        try (FileOutputStream out = new FileOutputStream(logFile(), true)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable e) { Log.e(TAG, "Cannot write model log", e); }
    }
    private static String reason(Throwable t) {
        if (t == null) return "unknown failure";
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m.trim();
    }
    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }
    private static String shQuote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
