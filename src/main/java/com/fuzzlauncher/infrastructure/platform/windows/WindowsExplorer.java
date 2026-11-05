package com.fuzzlauncher.infrastructure.platform.windows;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WindowsExplorer {
    private static final int RPC_E_CHANGED_MODE = 0x80010106;

    private WindowsExplorer() {
    }

    public static void reveal(Path source) throws IOException {
        Path target = source.toAbsolutePath().normalize();
        if (!Files.exists(target)) {
            throw new IOException("文件已不存在：" + target);
        }
        var ole = Ole32.INSTANCE;
        HRESULT apartment = ole.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED);
        // The calling thread may already have initialized COM in another apartment mode.
        if (apartment.intValue() != RPC_E_CHANGED_MODE) {
            check(apartment, "无法初始化 Windows Shell");
        }
        var item = new PointerByReference();
        try {
            var shell = Shell.INSTANCE;
            check(shell.SHParseDisplayName(new WString(target.toString()), null, item, 0, null), "无法解析文件路径");
            // An absolute item ID and a zero child count select that item in its parent folder.
            check(shell.SHOpenFolderAndSelectItems(item.getValue(), 0, null, 0), "资源管理器无法选中文件");
        } finally {
            if (item.getValue() != null) {
                ole.CoTaskMemFree(item.getValue());
            }
            if (apartment.intValue() >= 0) {
                ole.CoUninitialize();
            }
        }
    }

    private static void check(HRESULT result, String message) throws IOException {
        if (result.intValue() < 0) {
            throw new IOException("%s (0x%08X)".formatted(message, result.intValue()));
        }
    }

    private interface Shell extends StdCallLibrary {
        Shell INSTANCE = Native.load("shell32", Shell.class);

        HRESULT SHParseDisplayName(WString name, Pointer bindContext, PointerByReference item, int attributes, Pointer resultAttributes);

        HRESULT SHOpenFolderAndSelectItems(Pointer item, int childCount, Pointer children, int flags);
    }
}
