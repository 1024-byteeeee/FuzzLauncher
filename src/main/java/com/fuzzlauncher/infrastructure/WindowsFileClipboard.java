package com.fuzzlauncher.infrastructure;

import com.sun.jna.*;
import com.sun.jna.platform.win32.BaseTSD.SIZE_T;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Native Shell file clipboard: CF_HDROP plus Preferred DropEffect for Explorer cut/copy. */
public final class WindowsFileClipboard {
  private static final int CF_HDROP = 15, DROP_COPY = 1, DROP_MOVE = 2, GMEM_MOVEABLE = 2;
  private static final User USER = Native.load("user32", User.class);
  private static final Kernel KERNEL = Native.load("kernel32", Kernel.class);
  private static final Shell SHELL = Native.load("shell32", Shell.class);
  private static final int EFFECT =
      USER.RegisterClipboardFormatW(new WString("Preferred DropEffect"));

  private WindowsFileClipboard() {}

  public record Contents(List<Path> paths, boolean move, int sequence) {}

  public static boolean hasFiles() {
    return USER.IsClipboardFormatAvailable(CF_HDROP);
  }

  public static Contents read() throws IOException {
    open();
    try {
      Pointer drop = USER.GetClipboardData(CF_HDROP);
      if (drop == null) throw new IOException("剪贴板中没有文件或文件夹");
      int count = SHELL.DragQueryFileW(drop, -1, null, 0);
      if (count < 1 || count > 10000) throw new IOException("剪贴板文件数量无效");
      List<Path> paths = new ArrayList<>();
      for (int index = 0; index < count; index++) {
        int length = SHELL.DragQueryFileW(drop, index, null, 0);
        if (length < 1 || length > 32767) throw new IOException("剪贴板文件路径无效");
        char[] name = new char[length + 1];
        SHELL.DragQueryFileW(drop, index, name, name.length);
        paths.add(Path.of(new String(name, 0, length)).toAbsolutePath().normalize());
      }
      boolean move = false;
      Pointer effect = USER.GetClipboardData(EFFECT);
      if (effect != null) {
        Pointer data = KERNEL.GlobalLock(effect);
        if (data == null) throw error("无法读取剪贴板的剪切状态");
        try {
          move = (data.getInt(0) & DROP_MOVE) != 0;
        } finally {
          KERNEL.GlobalUnlock(effect);
        }
      }
      return new Contents(List.copyOf(paths), move, USER.GetClipboardSequenceNumber());
    } finally {
      USER.CloseClipboard();
    }
  }

  public static void write(List<Path> paths, boolean move) throws IOException {
    write(paths, move, null);
  }

  /** Removes only successful cuts, and never replaces clipboard contents changed by another app. */
  public static void completeCut(Contents original, List<Path> completed) throws IOException {
    List<Path> remaining =
        original.paths().stream().filter(path -> !completed.contains(path)).toList();
    write(remaining, true, original.sequence());
  }

  private static void write(List<Path> paths, boolean move, Integer expectedSequence)
      throws IOException {
    open();
    try {
      if (expectedSequence != null && USER.GetClipboardSequenceNumber() != expectedSequence) return;
      if (!USER.EmptyClipboard()) throw error("无法写入剪贴板");
      if (paths.isEmpty()) return;
      String names =
          String.join(
                  "\0",
                  paths.stream().map(path -> path.toAbsolutePath().normalize().toString()).toList())
              + "\0\0";
      byte[] text = names.getBytes(StandardCharsets.UTF_16LE);
      byte[] drop = new byte[20 + text.length];
      java.nio.ByteBuffer.wrap(drop)
          .order(java.nio.ByteOrder.LITTLE_ENDIAN)
          .putInt(0, 20)
          .putInt(16, 1);
      System.arraycopy(text, 0, drop, 20, text.length);
      publish(CF_HDROP, drop);
      publish(
          EFFECT,
          java.nio.ByteBuffer.allocate(4)
              .order(java.nio.ByteOrder.LITTLE_ENDIAN)
              .putInt(move ? DROP_MOVE : DROP_COPY)
              .array());
    } finally {
      USER.CloseClipboard();
    }
  }

  private static void publish(int format, byte[] bytes) throws IOException {
    Pointer memory = KERNEL.GlobalAlloc(GMEM_MOVEABLE, new SIZE_T(bytes.length));
    if (memory == null) throw error("无法分配剪贴板内存");
    boolean transferred = false;
    try {
      Pointer data = KERNEL.GlobalLock(memory);
      if (data == null) throw error("无法锁定剪贴板内存");
      try {
        data.write(0, bytes, 0, bytes.length);
      } finally {
        KERNEL.GlobalUnlock(memory);
      }
      if (USER.SetClipboardData(format, memory) == null) throw error("无法发布剪贴板数据");
      transferred = true;
    } finally {
      if (!transferred) KERNEL.GlobalFree(memory);
    }
  }

  private static void open() throws IOException {
    if (!USER.OpenClipboard(USER.GetForegroundWindow())) throw error("剪贴板被其他程序占用，请重试");
  }

  private static IOException error(String message) {
    return new IOException(message + "（Windows " + Native.getLastError() + "）");
  }

  private interface User extends StdCallLibrary {
    boolean OpenClipboard(Pointer owner);

    boolean CloseClipboard();

    boolean EmptyClipboard();

    Pointer GetClipboardData(int format);

    Pointer SetClipboardData(int format, Pointer data);

    int RegisterClipboardFormatW(WString name);

    int GetClipboardSequenceNumber();

    boolean IsClipboardFormatAvailable(int format);

    Pointer GetForegroundWindow();
  }

  private interface Kernel extends StdCallLibrary {
    Pointer GlobalAlloc(int flags, SIZE_T size);

    Pointer GlobalLock(Pointer memory);

    boolean GlobalUnlock(Pointer memory);

    Pointer GlobalFree(Pointer memory);
  }

  private interface Shell extends StdCallLibrary {
    int DragQueryFileW(Pointer drop, int index, char[] name, int length);
  }
}
