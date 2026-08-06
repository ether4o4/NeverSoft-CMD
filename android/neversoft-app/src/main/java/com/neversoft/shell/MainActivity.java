package com.neversoft.shell;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.neversoft.shell.ai.AiPaneController;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.File;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity implements TerminalSessionClient, TerminalViewClient {
    private static final String TAG = "NeverSoft";
    private static final int REQUEST_STORAGE = 1001;

    private TerminalView terminalView;
    private FrameLayout aiPane;
    private View splitHandle;
    private TextView statusText;
    private TerminalSession session;
    private AiPaneController aiController;
    private int terminalTextSize = 14;
    private boolean storageLinked;
    private boolean shellRevealed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        terminalView = findViewById(R.id.terminal_view);
        aiPane = findViewById(R.id.ai_pane);
        splitHandle = findViewById(R.id.split_handle);
        statusText = findViewById(R.id.status_text);
        terminalView.setTerminalViewClient(this);
        terminalView.setTextSize(terminalTextSize);
        terminalView.setVisibility(View.INVISIBLE);
        installTerminalToolbar();

        statusText.setText("Preparing NeverSoft shell...");
        BootstrapInstaller.ensureInstalled(this, new BootstrapInstaller.Callback() {
            @Override public void onReady() { runOnUiThread(MainActivity.this::prepareStorageThenStartShell); }
            @Override public void onError(String message, Throwable error) {
                Log.e(TAG, "Bootstrap install failed", error);
                runOnUiThread(() -> statusText.setText("NeverSoft bootstrap failed\n" + message));
            }
        });
    }

    private void installTerminalToolbar() {
        LinearLayout root = findViewById(R.id.root);
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setFillViewport(false);
        scroller.setBackgroundColor(Color.rgb(24, 24, 24));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(3), dp(4), dp(3));
        scroller.addView(row, new HorizontalScrollView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addKey(row, "⌨", this::showKeyboard);
        addKey(row, "CTRL+C", () -> sendBytes(new byte[] {3}));
        addKey(row, "CTRL+D", () -> sendBytes(new byte[] {4}));
        addKey(row, "ESC", () -> sendBytes(new byte[] {27}));
        addKey(row, "TAB", () -> sendBytes(new byte[] {9}));
        addKey(row, "←", () -> sendAnsi("\u001b[D"));
        addKey(row, "↑", () -> sendAnsi("\u001b[A"));
        addKey(row, "↓", () -> sendAnsi("\u001b[B"));
        addKey(row, "→", () -> sendAnsi("\u001b[C"));
        addKey(row, "HOME", () -> sendAnsi("\u001b[H"));
        addKey(row, "END", () -> sendAnsi("\u001b[F"));

        // Root order: AI, split rail, terminal, toolbar, status.
        root.addView(scroller, 3, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
    }

    private void addKey(LinearLayout row, String label, Runnable action) {
        Button key = new Button(this);
        key.setText(label);
        key.setTextSize(11);
        key.setTextColor(Color.WHITE);
        key.setAllCaps(false);
        key.setPadding(dp(8), 0, dp(8), 0);
        key.setMinWidth(0);
        key.setMinimumWidth(0);
        key.setBackgroundColor(Color.rgb(45, 45, 45));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        lp.setMargins(dp(2), 0, dp(2), 0);
        row.addView(key, lp);
        key.setOnClickListener(v -> action.run());
    }

    private void sendAnsi(String sequence) {
        sendBytes(sequence.getBytes(StandardCharsets.UTF_8));
    }

    private void sendBytes(byte[] data) {
        TerminalSession s = session;
        if (s != null && s.isRunning() && data != null && data.length > 0) {
            s.write(data, 0, data.length);
            terminalView.requestFocus();
        }
    }

    private void prepareStorageThenStartShell() {
        if (hasStoragePermission()) {
            setupStorageLinks();
            revealAndStartShell();
            return;
        }
        // Android 6+: shared storage is a runtime grant. Ask before touching
        // /storage/emulated/0 so the system shows the storage prompt.
        statusText.setText("NeverSoft needs storage access to reach your Downloads, "
            + "Documents and shared files.\n\nAllow it on the next prompt, or deny to "
            + "run with private app storage only.");
        requestPermissions(new String[] {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        }, REQUEST_STORAGE);
    }

    private boolean hasStoragePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
            || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_STORAGE) return;

        boolean granted = grantResults.length > 0;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) granted = false;
        }

        if (granted) {
            setupStorageLinks();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            // "Don't ask again" was selected — the system will no longer prompt.
            // Point the user at Settings so they can still enable it later.
            Toast.makeText(this,
                "Storage stays off. Enable it in App info › Permissions, then run "
                    + "storage-setup.", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this,
                "Running with private storage only. Run storage-setup after granting "
                    + "access to link shared folders.", Toast.LENGTH_LONG).show();
        }
        // The terminal is usable either way; never block shell start on storage.
        revealAndStartShell();
    }

    private void setupStorageLinks() {
        try {
            BootstrapInstaller.setupSharedStorage(this);
            storageLinked = true;
        } catch (Throwable t) {
            Log.w(TAG, "Shared storage setup unavailable", t);
        }
    }

    /** Open this app's system settings so the user can grant storage access. */
    @SuppressWarnings("unused")
    private void openAppStorageSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to open app settings", t);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // If the user granted storage from Settings while we were backgrounded,
        // link the shared folders now without requiring a restart.
        if (shellRevealed && !storageLinked && hasStoragePermission()) {
            setupStorageLinks();
        }
    }

    private void revealAndStartShell() {
        shellRevealed = true;
        statusText.setVisibility(View.GONE);
        terminalView.setVisibility(View.VISIBLE);
        if (aiController == null) aiController = new AiPaneController(this, aiPane, terminalView, splitHandle);
        terminalView.post(this::startShell);
    }

    private void startShell() {
        if (session != null && session.isRunning()) return;

        File home = ShellRuntime.home(this);
        File bash = new File(ShellRuntime.prefix(this), "bin/bash");
        if (!bash.isFile()) {
            statusText.setVisibility(View.VISIBLE);
            statusText.setText("bash is missing from the NeverSoft prefix.");
            return;
        }

        home.mkdirs();
        String executable = ShellRuntime.terminalExecutable(this);
        String[] args = ShellRuntime.terminalArgs(this);
        String[] env = BootstrapInstaller.shellEnvironment(this);
        session = new TerminalSession(executable, home.getAbsolutePath(), args, env, 5000, this);
        session.mSessionName = "NeverSoft_Shell";
        terminalView.attachSession(session);
        terminalView.requestFocus();
    }

    private void showKeyboard() {
        terminalView.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (aiController != null) aiController.destroy();
        if (isFinishing() && session != null) session.finishIfRunning();
        super.onDestroy();
    }

    @Override public void onTextChanged(TerminalSession changedSession) { if (changedSession == session) runOnUiThread(terminalView::onScreenUpdated); }
    @Override public void onTitleChanged(TerminalSession changedSession) {}

    @Override
    public void onSessionFinished(TerminalSession finishedSession) {
        runOnUiThread(() -> {
            statusText.setText("Shell exited — tap here to restart");
            statusText.setVisibility(View.VISIBLE);
            statusText.setOnClickListener(v -> { statusText.setVisibility(View.GONE); startShell(); });
        });
    }

    @Override
    public void onCopyTextToClipboard(TerminalSession terminalSession, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("NeverSoft terminal", text));
    }

    @Override
    public void onPasteTextFromClipboard(TerminalSession terminalSession) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip() || clipboard.getPrimaryClip() == null) return;
        ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
        CharSequence value = item.coerceToText(this);
        if (value != null && terminalSession.getEmulator() != null) terminalSession.getEmulator().paste(value.toString());
    }

    @Override public void onBell(TerminalSession terminalSession) {}
    @Override public void onColorsChanged(TerminalSession terminalSession) { runOnUiThread(terminalView::onScreenUpdated); }
    @Override public void onTerminalCursorStateChange(boolean state) { runOnUiThread(terminalView::onScreenUpdated); }
    @Override public Integer getTerminalCursorStyle() { return null; }

    @Override
    public float onScale(float scale) {
        if (scale > 1.12f) terminalTextSize = Math.min(32, terminalTextSize + 1);
        else if (scale < 0.88f) terminalTextSize = Math.max(8, terminalTextSize - 1);
        else return scale;
        terminalView.setTextSize(terminalTextSize);
        return 1.0f;
    }

    @Override public void onSingleTapUp(MotionEvent e) { showKeyboard(); }
    @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
    @Override public boolean shouldEnforceCharBasedInput() { return true; }
    @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
    @Override public boolean isTerminalViewSelected() { return true; }
    @Override public void copyModeChanged(boolean copyMode) {}
    @Override public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession currentSession) { return false; }
    @Override public boolean onKeyUp(int keyCode, KeyEvent e) { return false; }
    @Override public boolean onLongPress(MotionEvent event) { return false; }
    @Override public boolean readControlKey() { return false; }
    @Override public boolean readAltKey() { return false; }
    @Override public boolean readShiftKey() { return false; }
    @Override public boolean readFnKey() { return false; }
    @Override public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession terminalSession) { return false; }
    @Override public void onEmulatorSet() {}

    @Override public void logError(String tag, String message) { Log.e(tag, message); }
    @Override public void logWarn(String tag, String message) { Log.w(tag, message); }
    @Override public void logInfo(String tag, String message) { Log.i(tag, message); }
    @Override public void logDebug(String tag, String message) { Log.d(tag, message); }
    @Override public void logVerbose(String tag, String message) { Log.v(tag, message); }
    @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { Log.e(tag, message, e); }
    @Override public void logStackTrace(String tag, Exception e) { Log.e(tag, "Terminal exception", e); }
}
