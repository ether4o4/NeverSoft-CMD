package com.neversoft.shell.ai;

import android.content.Context;
import android.util.Log;

import com.neversoft.shell.ShellRuntime;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Runs Hugging Face GGUF models through llama.cpp in the shared NeverSoft shell. */
public final class LocalLlamaRuntime {
    private static final String TAG = "NeverSoftLlama";
    public static final int PORT = 8080;

    private final Context context;
    private volatile java.lang.Process process;

    public LocalLlamaRuntime(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized boolean isRunning() {
        java.lang.Process p = process;
        if (p == null) return false;
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException stillRunning) {
            return true;
        }
    }

    public synchronized void ensureInstalled() throws Exception {
        File server = new File(ShellRuntime.prefix(context), "bin/llama-server");
        if (server.isFile()) return;
        ShellRuntime.ensurePackages(context, "llama-cpp");
        if (!server.isFile()) throw new IllegalStateException("llama-cpp installed but llama-server is missing");
    }

    public synchronized void start(File model) throws Exception {
        if (model == null || !model.isFile() || model.length() == 0) {
            throw new IllegalArgumentException("GGUF model file is missing");
        }
        stop();
        ensureInstalled();

        String command = "exec llama-server -m " + shQuote(model.getAbsolutePath()) +
            " --host 127.0.0.1 --port " + PORT + " --ctx-size 4096";
        ProcessBuilder pb = ShellRuntime.processBuilder(context, command);
        java.lang.Process p = pb.start();
        process = p;

        Thread drain = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) Log.d(TAG, line);
            } catch (Exception e) {
                Log.d(TAG, "llama-server output closed", e);
            }
        }, "NeverSoft-llama-output");
        drain.setDaemon(true);
        drain.start();

        long deadline = System.currentTimeMillis() + 90_000L;
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning()) throw new IllegalStateException("llama-server exited during startup");
            if (healthCheck()) return;
            Thread.sleep(500L);
        }
        stop();
        throw new IllegalStateException("llama-server did not become ready within 90 seconds");
    }

    public synchronized void stop() {
        java.lang.Process p = process;
        process = null;
        if (p != null) {
            try { p.destroy(); } catch (Throwable ignored) {}
            try {
                Thread.sleep(100L);
                p.destroyForcibly();
            } catch (Throwable ignored) {}
        }
    }

    public boolean healthCheck() {
        try {
            HttpURLConnection c = (HttpURLConnection)new URL("http://127.0.0.1:" + PORT + "/health").openConnection();
            c.setConnectTimeout(800);
            c.setReadTimeout(800);
            int code = c.getResponseCode();
            c.disconnect();
            return code >= 200 && code < 500;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Non-streaming OpenAI-compatible chat request to the local llama-server. */
    public String chat(List<JSONObject> history, String systemPrompt) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("model", "local-huggingface");
        payload.put("stream", false);
        payload.put("temperature", 0.4);

        JSONArray messages = new JSONArray();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        }
        if (history != null) for (JSONObject item : history) messages.put(item);
        payload.put("messages", messages);

        HttpURLConnection c = (HttpURLConnection)new URL("http://127.0.0.1:" + PORT + "/v1/chat/completions").openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(180_000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = c.getOutputStream()) { out.write(body); }

        int code = c.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
            code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream(), StandardCharsets.UTF_8));
        StringBuilder text = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) text.append(line);
        reader.close();
        c.disconnect();
        if (code < 200 || code >= 300) throw new IllegalStateException("Local AI HTTP " + code + ": " + text);

        JSONObject json = new JSONObject(text.toString());
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new IllegalStateException("Local AI returned no choices");
        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
        if (message == null) throw new IllegalStateException("Local AI response is missing message");
        return message.optString("content", "").trim();
    }

    private static String shQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
