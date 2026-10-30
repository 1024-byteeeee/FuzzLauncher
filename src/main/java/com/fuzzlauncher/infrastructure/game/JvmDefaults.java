package com.fuzzlauncher.infrastructure.game;

import com.fuzzlauncher.config.LauncherConfig;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;

/** HMCL-style defaults, added only when the user or version has not supplied the option. */
public final class JvmDefaults {
    private JvmDefaults() {
    }

    public static List<String> generate(JvmEnvironment environment, List<String> existing) {
        Set<String> occupied = new HashSet<>();
        existing.forEach(argument -> occupied.add(key(argument)));
        boolean otherGc = existing.stream()
            .anyMatch(argument -> argument.equals("-XX:-UseG1GC") || argument.matches("-XX:\\+Use(?!G1GC$).*GC"));
        boolean disabledUnlock = existing.contains("-XX:-UnlockExperimentalVMOptions")
            || existing.contains("-XX:-UnlockDiagnosticVMOptions");
        boolean customProxy = existing.stream()
            .anyMatch(argument -> argument.startsWith("-Djava.net.useSystemProxies") || argument.startsWith("-Dhttp.proxy")
                || argument.startsWith("-Dhttps.proxy") || argument.startsWith("-DsocksProxy") || argument.startsWith("-Djava.net.socks."));
        String encoding = existing.stream().filter(argument -> argument.startsWith("-Dfile.encoding=")).reduce((first, last) -> last)
            .map(argument -> argument.substring("-Dfile.encoding=".length())).filter(value -> !value.equals("COMPAT"))
            .orElse(environment.encoding());
        List<String> result = new ArrayList<>();
        for (var rule : LauncherConfig.get().jvmRules()) {
            if (environment.major() < rule.minJava() || environment.major() > rule.maxJava()) {
                continue;
            }
            boolean enabled = switch (rule.group()) {
                case "common" -> true;
                case "windows" -> System.getProperty("os.name", "").startsWith("Windows");
                case "windows10" ->
                    System.getProperty("os.name", "").startsWith("Windows") && System.getProperty("os.version", "").startsWith("10.");
                case "32bit" -> !environment.is64Bit();
                case "hotspot" -> environment.hotspot() && !disabledUnlock;
                case "g1" -> environment.hotspot() && !disabledUnlock && !otherGc;
                case "large64" -> environment.hotspot() && !disabledUnlock && environment.is64Bit()
                    && environment.physicalMemory() > 4L * 1024 * 1024 * 1024;
                case "compact64" -> environment.hotspot() && !disabledUnlock && environment.is64Bit();
                default -> throw new IllegalArgumentException("未知 JVM 规则分组：" + rule.group());
            };
            if (!enabled) {
                continue;
            }
            for (String template : rule.arguments()) {
                String argument = template.replace("${encoding}", encoding).replace("${launcherVersion}",
                    com.fuzzlauncher.Launcher.VERSION);
                if (customProxy && argument.equals("-Djava.net.useSystemProxies=true")) {
                    continue;
                }
                if (argument.startsWith("-XX:") && !environment.flags().contains(key(argument).substring(4))) {
                    continue;
                }
                if (occupied.add(key(argument))) {
                    result.add(argument);
                }
            }
        }
        return List.copyOf(result);
    }

    static String key(String argument) {
        if (argument.startsWith("-XX:")) {
            return "-XX:" + argument.substring(4).replaceFirst("^[+-]", "").split("=", 2)[0];
        }
        if (argument.startsWith("-D") || argument.startsWith("--")) {
            return argument.split("=", 2)[0];
        }
        for (String memory : List.of("-Xmx", "-Xms", "-Xss", "-Xmn")) {
            if (argument.startsWith(memory)) {
                return memory;
            }
        }
        return argument;
    }

    public static Charset outputEncoding(JvmEnvironment environment, List<String> jvmArguments) throws IOException {
        String fileEncoding = environment.encoding();
        String outputEncoding = null;
        String prefix = environment.major() < 19 ? "-Dsun.stdout.encoding=" : "-Dstdout.encoding=";
        for (String argument : jvmArguments) {
            if (argument.startsWith("-Dfile.encoding=")) {
                fileEncoding = argument.substring("-Dfile.encoding=".length());
            }
            if (argument.startsWith(prefix)) {
                outputEncoding = argument.substring(prefix.length());
            }
        }
        String name = outputEncoding != null ? outputEncoding : fileEncoding;
        if (name.equals("COMPAT")) {
            name = environment.encoding();
        }
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException ex) {
            throw new IOException("JVM 输出编码无效：" + name, ex);
        }
    }
}
