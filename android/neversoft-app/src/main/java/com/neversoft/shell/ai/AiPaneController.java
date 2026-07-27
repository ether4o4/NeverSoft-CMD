package com.neversoft.shell.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.neversoft.shell.ShellRuntime;

import org.json.JSONObject;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Minimal Windows-11-inspired top AI pane backed by local Hugging Face GGUF. */
public final class AiPaneController {
    private final Activity activity;
    private final FrameLayout aiPane;
    private final View terminalPane;
    private final View splitHandle;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LocalLlamaRuntime llama;
    private final AiConfig config = new AiConfig();
    private final List<JSONObject> history = new ArrayList<>();

    private TextView transcript;
    private TextView status;
    private EditText messageInput;
    private EditText modelUrlInput;
    private EditText tokenInput;
    private Button sendButton;
    private Button loadButton;
    private ScrollView transcriptScroll;
    private volatile boolean busy;

    public AiPaneController(Activity activity, FrameLayout aiPane, View terminalPane, View splitHandle) {
        this.activity = activity;
        this.aiPane = aiPane;
        this.terminalPane = terminalPane;
        this.splitHandle = splitHandle;
        this.llama = new LocalLlamaRuntime(activity);
        buildUi();
        enableSplit();
    }

    public void destroy() {
        llama.stop();
        worker.shutdownNow();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(32, 32, 32));
        int pad = dp(10);
        root.setPadding(pad, pad, pad, dp(6));
        aiPane.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout titleRow = new LinearLayout(activity);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("NeverSoft AI", 14, Color.rgb(242, 242, 242));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        status = text("HF LOCAL · model not loaded", 11, Color.rgb(160, 160, 160));
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(status);
        root.addView(titleRow);

        LinearLayout setup = new LinearLayout(activity);
        setup.setOrientation(LinearLayout.HORIZONTAL);
        setup.setPadding(0, dp(7), 0, dp(6));
        modelUrlInput = field("Hugging Face .gguf URL", false);
        tokenInput = field("HF token (optional)", true);
        loadButton = button("Load");
        setup.addView(modelUrlInput, new LinearLayout.LayoutParams(0, dp(40), 2.4f));
        LinearLayout.LayoutParams tokenLp = new LinearLayout.LayoutParams(0, dp(40), 1.35f);
        tokenLp.setMargins(dp(6), 0, 0, 0);
        setup.addView(tokenInput, tokenLp);
        LinearLayout.LayoutParams loadLp = new LinearLayout.LayoutParams(dp(66), dp(40));
        loadLp.setMargins(dp(6), 0, 0, 0);
        setup.addView(loadButton, loadLp);
        root.addView(setup);

        transcript = text("Paste a Hugging Face GGUF URL above. NeverSoft will download it locally, install llama.cpp in the shell, and run the model on-device.\n", 12, Color.rgb(224, 224, 224));
        transcript.setTypeface(Typeface.MONOSPACE);
        transcript.setTextIsSelectable(true);
        transcriptScroll = new ScrollView(activity);
        transcriptScroll.setFillViewport(true);
        transcriptScroll.addView(transcript, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(transcriptScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout composer = new LinearLayout(activity);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setPadding(0, dp(6), 0, 0);
        messageInput = field("Ask NeverSoft AI…", false);
        sendButton = button("Send");
        sendButton.setEnabled(false);
        composer.addView(messageInput, new LinearLayout.LayoutParams(0, dp(42), 1f));
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(dp(72), dp(42));
        sendLp.setMargins(dp(6), 0, 0, 0);
        composer.addView(sendButton, sendLp);
        root.addView(composer);

        loadButton.setOnClickListener(v -> loadModel());
        sendButton.setOnClickListener(v -> sendMessage());
    }

    private void enableSplit() {
        aiPane.setVisibility(View.VISIBLE);
        splitHandle.setVisibility(View.VISIBLE);
        setSplit(0.5f);

        final float[] startY = new float[1];
        final float[] startSplit = new float[] {0.5f};
        splitHandle.setOnTouchListener((v, event) -> {
            View parent = (View)v.getParent();
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                startY[0] = event.getRawY();
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams)aiPane.getLayoutParams();
                startSplit[0] = lp.weight / Math.max(0.001f, lp.weight + ((LinearLayout.LayoutParams)terminalPane.getLayoutParams()).weight);
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float h = Math.max(1f, parent.getHeight());
                float next = startSplit[0] + (event.getRawY() - startY[0]) / h;
                setSplit(Math.max(0.18f, Math.min(0.82f, next)));
                return true;
            }
            return event.getActionMasked() == MotionEvent.ACTION_UP;
        });
    }

    private void setSplit(float ratio) {
        LinearLayout.LayoutParams aiLp = (LinearLayout.LayoutParams)aiPane.getLayoutParams();
        aiLp.height = 0;
        aiLp.weight = ratio;
        aiPane.setLayoutParams(aiLp);
        LinearLayout.LayoutParams termLp = (LinearLayout.LayoutParams)terminalPane.getLayoutParams();
        termLp.height = 0;
        termLp.weight = 1f - ratio;
        terminalPane.setLayoutParams(termLp);
    }

    private void loadModel() {
        if (busy) return;
        String url = modelUrlInput.getText().toString().trim();
        String token = tokenInput.getText().toString().trim();
        if (url.isEmpty()) {
            setStatus("HF LOCAL · paste a .gguf URL");
            return;
        }
        HuggingFaceModelManager.setToken(activity, token);
        String outputName = nameFromUrl(url);
        busy = true;
        loadButton.setEnabled(false);
        sendButton.setEnabled(false);
        worker.execute(() -> {
            try {
                File model = HuggingFaceModelManager.download(activity, url, outputName,
                    (phase, pct) -> setStatus("HF LOCAL · " + phase + " " + pct + "%"));
                setStatus("HF LOCAL · installing llama.cpp…");
                llama.start(model);
                setStatus("HF LOCAL · ready · " + model.getName());
                append("\n[local model ready: " + model.getName() + "]\n");
                main.post(() -> sendButton.setEnabled(true));
            } catch (Throwable t) {
                append("\n[AI setup failed: " + safeMessage(t) + "]\n");
                setStatus("HF LOCAL · setup failed");
            } finally {
                busy = false;
                main.post(() -> loadButton.setEnabled(true));
            }
        });
    }

    private void sendMessage() {
        if (busy || !llama.isRunning()) return;
        String text = messageInput.getText().toString().trim();
        if (text.isEmpty()) return;
        messageInput.setText("");
        append("\nYOU > " + text + "\n");
        history.add(message("user", text));
        busy = true;
        sendButton.setEnabled(false);
        worker.execute(() -> {
            try {
                runAgentLoop();
            } catch (Throwable t) {
                append("\n[AI error: " + safeMessage(t) + "]\n");
            } finally {
                busy = false;
                main.post(() -> sendButton.setEnabled(llama.isRunning()));
            }
        });
    }

    private void runAgentLoop() throws Exception {
        for (int step = 0; step < 8; step++) {
            setStatus("HF LOCAL · thinking…");
            String full = llama.chat(history, config.systemPrompt);
            history.add(message("assistant", full));

            String visible = AgentProtocol.stripRunBlocks(full);
            if (!visible.isEmpty()) append("AI > " + visible + "\n");
            List<String> commands = AgentProtocol.parseRunBlocks(full);
            if (commands.isEmpty()) {
                setStatus("HF LOCAL · ready");
                return;
            }

            StringBuilder script = new StringBuilder();
            for (String command : commands) {
                CommandRisk.Result risk = CommandRisk.analyze(command);
                if (risk.requiresApproval(config.autoRunSafe) && !requestApproval(command, risk)) {
                    append("$ " + command + "\n[blocked]\n");
                    history.add(message("user", "[shell output]\nCommand was blocked by the user: " + command));
                    script.setLength(0);
                    break;
                }
                script.append(command).append('\n');
            }
            if (script.length() == 0) continue;

            setStatus("HF LOCAL · working…");
            append("$ " + script.toString().trim().replace("\n", "\n$ ") + "\n");
            ShellRuntime.Result result = ShellRuntime.run(activity, script.toString());
            String output = result.output == null || result.output.isEmpty() ? "(no output)" : result.output;
            append(output + "\n");
            if (output.length() > 6000) output = output.substring(0, 6000) + "\n…(truncated)";
            history.add(message("user", "[shell output, exit=" + result.exitCode + "]\n" + output));
        }
        append("AI > Paused after 8 tool steps. Send 'continue' to keep going.\n");
        setStatus("HF LOCAL · ready");
    }

    private boolean requestApproval(String command, CommandRisk.Result risk) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean approved = new AtomicBoolean(false);
        main.post(() -> new AlertDialog.Builder(activity)
            .setTitle("Allow AI command?")
            .setMessage(command + "\n\n" + String.join("\n", risk.reasons))
            .setNegativeButton("Block", (d, w) -> latch.countDown())
            .setPositiveButton("Run", (d, w) -> { approved.set(true); latch.countDown(); })
            .setOnCancelListener(d -> latch.countDown())
            .show());
        latch.await();
        return approved.get();
    }

    private JSONObject message(String role, String content) {
        try { return new JSONObject().put("role", role).put("content", content); }
        catch (Exception impossible) { return new JSONObject(); }
    }

    private void setStatus(String value) {
        main.post(() -> status.setText(value));
    }

    private void append(String value) {
        main.post(() -> {
            transcript.append(value);
            transcriptScroll.post(() -> transcriptScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private String nameFromUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            return name.isEmpty() ? "model.gguf" : name;
        } catch (Exception ignored) {
            return "model.gguf";
        }
    }

    private String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private TextView text(String value, int sp, int color) {
        TextView v = new TextView(activity);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private EditText field(String hint, boolean password) {
        EditText v = new EditText(activity);
        v.setSingleLine(true);
        v.setHint(hint);
        v.setHintTextColor(Color.rgb(150, 150, 150));
        v.setTextColor(Color.rgb(245, 245, 245));
        v.setTextSize(12);
        v.setPadding(dp(9), 0, dp(9), 0);
        v.setBackgroundColor(Color.rgb(45, 45, 45));
        if (password) v.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return v;
    }

    private Button button(String label) {
        Button v = new Button(activity);
        v.setText(label);
        v.setTextSize(11);
        v.setTextColor(Color.WHITE);
        v.setAllCaps(false);
        v.setBackgroundColor(Color.rgb(15, 108, 189));
        v.setPadding(0, 0, 0, 0);
        return v;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
