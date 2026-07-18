package com.lumin.ssh.android;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

public class LuminTerminalSessionClient implements TerminalSessionClient {
    private final Runnable invalidate;

    public LuminTerminalSessionClient(Runnable invalidate) {
        this.invalidate = invalidate;
    }

    @Override public void onTextChanged(TerminalSession changedSession) { invalidate.run(); }
    @Override public void onTitleChanged(TerminalSession updatedSession) {}
    @Override public void onSessionFinished(TerminalSession finishedSession) {}
    @Override public void onCopyTextToClipboard(TerminalSession session, String text) {}
    @Override public void onPasteTextFromClipboard(TerminalSession session) {}
    @Override public void onBell(TerminalSession session) {}
    @Override public void onColorsChanged(TerminalSession session) { invalidate.run(); }
    @Override public void onTerminalCursorStateChange(boolean state) {}
    @Override public void setTerminalShellPid(TerminalSession session, int pid) {}
    @Override public Integer getTerminalCursorStyle() { return TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE; }
    @Override public void logError(String tag, String message) {}
    @Override public void logWarn(String tag, String message) {}
    @Override public void logInfo(String tag, String message) {}
    @Override public void logDebug(String tag, String message) {}
    @Override public void logVerbose(String tag, String message) {}
    @Override public void logStackTraceWithMessage(String tag, String message, Exception e) {}
    @Override public void logStackTrace(String tag, Exception e) {}
}
