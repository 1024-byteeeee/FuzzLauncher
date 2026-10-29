package com.fuzzlauncher.application;

import java.util.concurrent.CancellationException;

public interface TaskContext {
  void report(String message, double progress);

  boolean cancelled();

  default void deviceLogin(DeviceLogin challenge) {}

  default void checkCancelled() {
    if (cancelled() || Thread.currentThread().isInterrupted())
      throw new CancellationException("任务已取消");
  }
}
