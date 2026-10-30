package com.fuzzlauncher.infrastructure.game;

import com.fuzzlauncher.infrastructure.ProcessCleanup;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Queries the selected runtime, independently of the launcher's bundled JVM. */
public record JvmEnvironment(int major, boolean is64Bit, boolean hotspot, long physicalMemory, String encoding, Set<String> flags) {
    public JvmEnvironment {
        flags = Set.copyOf(flags);
    }

    public static JvmEnvironment inspect(Path executable) throws IOException, InterruptedException {
        String properties = probe(executable, List.of("-XshowSettings:properties", "-version"));
        String version = property(properties, "java.version", "");
        var matcher = Pattern.compile("(?:1\\.)?(\\d+)").matcher(version);
        if (!matcher.find()) {
            throw new IOException("无法识别游戏 Java 版本");
        }
        int major = Integer.parseInt(matcher.group(1));
        String vm = property(properties, "java.vm.name", "").toLowerCase(Locale.ROOT);
        boolean hotspot = (vm.contains("hotspot") || vm.contains("openjdk") || vm.contains("server vm")) && !vm.contains("openj9")
            && !vm.contains("j9");
        Set<String> flags = new HashSet<>();
        if (hotspot) {
            String output = probe(executable,
                List.of("-XX:+UnlockExperimentalVMOptions", "-XX:+UnlockDiagnosticVMOptions", "-XX:+PrintFlagsFinal", "-version"));
            var flag = Pattern.compile("(?m)^\\s*\\w+\\s+(\\w+)\\s*(?::=|=)").matcher(output);
            while (flag.find()) {
                flags.add(flag.group(1));
            }
        }
        String encoding = property(properties, "native.encoding",
            property(properties, "sun.jnu.encoding", property(properties, "file.encoding", "UTF-8")));
        if (!Charset.isSupported(encoding)) {
            throw new IOException("游戏 Java 返回了无法识别的系统编码：" + encoding);
        }
        long memory = ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean bean
            ? bean.getTotalMemorySize()
            : 0;
        return new JvmEnvironment(major, "64".equals(property(properties, "sun.arch.data.model", "")), hotspot, memory, encoding, flags);
    }

    private static String property(String text, String name, String fallback) {
        var match = Pattern.compile("(?m)^\\s*" + Pattern.quote(name) + " = (.+)$").matcher(text);
        return match.find() ? match.group(1).strip() : fallback;
    }

    private static String probe(Path executable, List<String> arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(arguments);
        Path output = Files.createTempFile("fuzz-java-probe-", ".txt");
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                throw new IOException("游戏 Java 参数检测超时");
            }
            if (process.exitValue() != 0 || Files.size(output) > 2 * 1024 * 1024) {
                throw new IOException("无法检测游戏 Java 支持的参数，请检查 Java 安装");
            }
            // Only ASCII property names/values and VM flags are consumed; native Windows
            // output can contain unrelated paths encoded with the system code page.
            return Files.readString(output, StandardCharsets.ISO_8859_1);
        } finally {
            try {
                if (process != null) {
                    ProcessCleanup.terminate(process);
                }
            } finally {
                Files.deleteIfExists(output);
            }
        }
    }
}
