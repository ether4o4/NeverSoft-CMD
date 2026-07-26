package com.neversoft.shell;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.File;

public final class MainActivity extends Activity implements TerminalSessionClient, TerminalViewClient {
    private static final String TAG = "NeverSoft";

    private TerminalView terminalView;
    private TextView statusText;
    private TerminalSession session;
    private int terminalTextSize = 14;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        terminalView = findViewById(R.id.terminal_view);
        statusText = findViewById(R.id.status_text);
        terminalView.setTerminalViewClient(this);
        terminalView.setTextSize(terminalTextSize);
        terminalView.setVisibility(View.INVISIBLE);

        statusText.setText("Preparing NeverSoft native shell...");
        BootstrapInstaller.ensureInstalled(this, new BootstrapInstaller.Callback() {
            @Override
            public void onReady() {
                runOnUiThread(() -> {
                    statusText.setVisibility(View.GONE);
                    terminalView.setVisibility(View.VISIBLE);
                    terminalView.post(MainActivity.this::startShell);
                });
            }

            @Override
            public void onError(String message, Throwable error) {
                Log.e(TAG, "Bootstrap install failed", error);
                runOnUiThread(() -> statusText.setText("NeverSoft bootstrap failed\n" + message));
            }
        });
    }

    private void startShell() {
        if (session != null && session.isRunning()) return;

        File rootfs = getFilesDir();
        File prefix = new File(rootfs, "usr");
        File home = new File(rootfs, "home");
        File bash = new File(prefix, "bin/bash");
        if (!bash.isFile()) {
            statusText.setVisibility(View.VISIBLE);
            statusText.setText("Native bash is missing from the NeverSoft prefix.");
            return;
        }

        home.mkdirs();
        String shellPath = bash.getAbsolutePath();
        String[] args = new String[] { shellPath, "-l" };
        String[] env = BootstrapInstaller.shellEnvironment(this);
        session = new TerminalSession(shellPath, home.getAbsolutePath(), args, env, 5000, this);
        session.mSessionName = "NeverSoft_Shell";
        terminalView.attachSession(session);
        terminalView.requestFocus();
        showKeyboard();
    }

    private void showKeyboard() {
        terminalView.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
    }

    @Override
    protected void onDestroy() {
        if (isFinishing() && session != null) session.finishIfRunning();
        super.onDestroy();
    }

    // TerminalSessionClient

    @Override
    public void onTextChanged(TerminalSession changedSession) {
        if (changedSession == session) runOnUiThread(terminalView::onScreenUpdated);
    }

    @Override
    public void onTitleChanged(TerminalSession changedSession) {
        // NeverSoft keeps its own shell chrome; escape-sequence titles do not replace the app identity.
    }

    @Override
    public void onSessionFinished(TerminalSession finishedSession) {
        runOnUiThread(() -> {
            statusText.setText("Shell exited — tap here to restart");
            statusText.setVisibility(View.VISIBLE);
            statusText.setOnClickListener(v -> {
                statusText.setVisibility(View.GONE);
                startShell();
            });
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

    // TerminalViewClient

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
