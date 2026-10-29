package com.fuzzlauncher.application;

import java.util.concurrent.CancellationException;

public interface TaskContext {
  void report(String message, double progress);

  boolean cancelled();

  @FunctionalInterface
  interface Commit {
    void run() throws Exception;
  }

  default void commit(Commit action) throws Exception {
    checkCancelled();
    action.run();
  }

  default void checkCancelled() {
    if (cancelled() || Thread.currentThread().isInterrupted())
      throw new CancellationException("任务已取消");
  }
}
