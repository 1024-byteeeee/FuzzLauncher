package com.fuzzlauncher.application.port;

import com.fuzzlauncher.application.model.JavaRuntimeInfo;

import java.util.List;

/** Lists the Java runtimes detected on the local machine. */
public interface JavaRuntimeCatalog {
    List<JavaRuntimeInfo> detected() throws Exception;
}
