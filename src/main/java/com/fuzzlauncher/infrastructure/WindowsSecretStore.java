package com.fuzzlauncher.infrastructure;

import com.sun.jna.platform.win32.Crypt32Util;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** DPAPI protects secrets for the current Windows user. No plaintext fallback. */
public final class WindowsSecretStore {
  private final Path file;

  public WindowsSecretStore(Path file) {
    this.file = file;
  }

  public synchronized String read() throws IOException {
    if (!Files.exists(file)) return "";
    try {
      return new String(
          Crypt32Util.cryptUnprotectData(Files.readAllBytes(file)), StandardCharsets.UTF_8);
    } catch (RuntimeException ex) {
      throw new IOException("无法解密账号凭据，请使用保存凭据时的 Windows 用户，或重新登录", ex);
    }
  }

  public synchronized void write(String value) throws IOException {
    try {
      JsonFiles.atomicWrite(
          file, Crypt32Util.cryptProtectData(value.getBytes(StandardCharsets.UTF_8)));
    } catch (RuntimeException ex) {
      throw new IOException("Windows 凭据加密失败", ex);
    }
  }
}
