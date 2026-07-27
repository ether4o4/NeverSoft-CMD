package com.neversoft.shell.ai;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Native Hugging Face GGUF downloader for NeverSoft local AI. */
public final class HuggingFaceModelManager {
    public interface Progress {
        void onProgress(String phase, int percent);
    }

    private static final String PREFS = "neversoft_ai";
    private static final String HF_TOKEN = "hf_token";

    private HuggingFaceModelManager() {}

    public static File modelsDir(Context context) {
        File dir = new File(context.getFilesDir(), "models");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    public static void setToken(Context context, String token) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(HF_TOKEN, token == null ? "" : token.trim()).apply();
    }

    public static String getToken(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(HF_TOKEN, "");
    }

    public static List<File> listModels(Context context) {
        List<File> out = new ArrayList<>();
        File[] files = modelsDir(context).listFiles();
        if (files != null) {
            for (File f : files) if (f.isFile() && f.getName().toLowerCase().endsWith(".gguf") && f.length() > 0) out.add(f);
        }
        return out;
    }

    /**
     * Build a normal Hugging Face resolve URL from repo + filename.
     * Example repo: owner/model-GGUF, file: model.Q4_K_M.gguf
     */
    public static String resolveUrl(String repo, String revision, String filename) {
        String rev = revision == null || revision.trim().isEmpty() ? "main" : revision.trim();
        return "https://huggingface.co/" + repo.trim() + "/resolve/" + rev + "/" + filename.trim() + "?download=true";
    }

    public static File download(Context context, String url, String outputName, Progress progress) throws Exception {
        if (url == null || !(url.startsWith("https://huggingface.co/") || url.startsWith("https://hf.co/"))) {
            throw new IllegalArgumentException("NeverSoft local model downloads must come from huggingface.co/hf.co");
        }
        String name = sanitizeName(outputName);
        if (!name.toLowerCase().endsWith(".gguf")) name += ".gguf";

        File dest = new File(modelsDir(context), name);
        File part = new File(dest.getAbsolutePath() + ".part");
        if (part.exists()) part.delete();

        String token = getToken(context);
        String current = url;
        HttpURLConnection connection = null;
        for (int redirects = 0; redirects < 8; redirects++) {
            URL u = new URL(current);
            connection = (HttpURLConnection) u.openConnection();
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(60_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "NeverSoft-CMD/0.1");
            if (!token.isEmpty() && isHuggingFaceHost(u.getHost())) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String next = connection.getHeaderField("Location");
                connection.disconnect();
                if (next == null || next.isEmpty()) throw new IllegalStateException("Hugging Face redirect missing Location");
                current = new URL(u, next).toString();
                continue;
            }
            if (code < 200 || code >= 300) {
                connection.disconnect();
                throw new IllegalStateException("Hugging Face download failed: HTTP " + code);
            }
            break;
        }
        if (connection == null) throw new IllegalStateException("Unable to open Hugging Face download");

        long total = connection.getContentLengthLong();
        progress(progress, "Downloading model", 0);
        try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(part)) {
            byte[] buffer = new byte[64 * 1024];
            long done = 0;
            int last = -1;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                output.write(buffer, 0, read);
                done += read;
                if (total > 0) {
                    int pct = (int)Math.min(99, (done * 100L) / total);
                    if (pct != last) {
                        last = pct;
                        progress(progress, "Downloading model", pct);
                    }
                }
            }
        } catch (Throwable t) {
            part.delete();
            throw t;
        } finally {
            connection.disconnect();
        }

        if (dest.exists() && !dest.delete()) throw new IllegalStateException("Unable to replace old model: " + dest);
        if (!part.renameTo(dest)) {
            part.delete();
            throw new IllegalStateException("Unable to finalize model download");
        }
        progress(progress, "Model ready", 100);
        return dest;
    }

    private static void progress(Progress progress, String phase, int pct) {
        if (progress != null) progress.onProgress(phase, pct);
    }

    private static boolean isHuggingFaceHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase();
        return h.equals("huggingface.co") || h.endsWith(".huggingface.co") || h.equals("hf.co") || h.endsWith(".hf.co");
    }

    private static String sanitizeName(String value) {
        String name = value == null ? "model.gguf" : value.trim();
        if (name.isEmpty()) name = "model.gguf";
        name = name.replaceAll("[^A-Za-z0-9._-]+", "_");
        while (name.startsWith(".")) name = name.substring(1);
        return name.isEmpty() ? "model.gguf" : name;
    }
}
