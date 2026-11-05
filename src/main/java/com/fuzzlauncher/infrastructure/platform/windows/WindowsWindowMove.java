package com.fuzzlauncher.infrastructure.platform.windows;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.win32.StdCallLibrary;

/** Hands a custom title-bar drag to the Windows window manager. */
public final class WindowsWindowMove {
    private static final int WM_NCLBUTTONDOWN = 0x00A1;
    private static final int HTCAPTION = 2;

    private WindowsWindowMove() {
    }

    public static boolean begin() {
        if (!Platform.isWindows()) {
            return false;
        }
        User32 user = User32.INSTANCE;
        HWND window = user.GetActiveWindow();
        if (window == null || !window.equals(user.GetForegroundWindow())) {
            return false;
        }
        POINT cursor = new POINT();
        if (!user.GetCursorPos(cursor)) {
            return false;
        }
        long position = (cursor.x & 0xffffL) | ((cursor.y & 0xffffL) << 16);
        Messages.INSTANCE.ReleaseCapture();
        // Post rather than send, so the native move loop starts after this UI event returns.
        return Messages.INSTANCE.PostMessageW(window, WM_NCLBUTTONDOWN, new WPARAM(HTCAPTION), new LPARAM(position));
    }

    private interface Messages extends StdCallLibrary {
        Messages INSTANCE = Native.load("user32", Messages.class);

        boolean ReleaseCapture();

        boolean PostMessageW(HWND window, int message, WPARAM wParam, LPARAM lParam);
    }
}
