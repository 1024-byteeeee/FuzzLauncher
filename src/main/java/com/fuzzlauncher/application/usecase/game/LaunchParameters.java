package com.fuzzlauncher.application.usecase.game;

import com.fuzzlauncher.domain.instance.GameInstance;

import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/** Command-line tokenization and memory validation, independent of JavaFX and protocol JSON. */
public final class LaunchParameters {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private LaunchParameters() {
    }

    public record MemoryArguments(Integer minimum, Integer maximum, List<String> jvmArguments) {
    }

    public static MemoryArguments parseMemory(String text, List<String> defaults) throws IOException {
        List<String> tokens = splitLegacy(text);
        List<String> rest = new ArrayList<>();
        Integer minimum = null, maximum = null;
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.startsWith("-Xms") || token.startsWith("-Xmx")) {
                boolean initialHeap = token.startsWith("-Xms");
                String prefix = initialHeap ? "-Xms" : "-Xmx";
                boolean separated = token.equals(prefix);
                Integer value = memoryValue(token, prefix, i, tokens);
                int minimumValue = initialHeap ? 128 : 512;
                if (value < minimumValue) {
                    throw new IOException(prefix + " 应在 " + minimumValue + "–65536 MB 之间");
                }
                if (separated) {
                    i++;
                }
                if (initialHeap) {
                    minimum = value;
                } else {
                    maximum = value;
                }
                continue;
            }
            if (defaults.contains(token)) {
                continue;
            }
            rest.add(token);
        }
        return new MemoryArguments(minimum, maximum, rest);
    }

    public static String display(List<String> tokens) {
        return tokens.stream().map(LaunchParameters::quote).collect(java.util.stream.Collectors.joining(" "));
    }

    private static String quote(String token) {
        if (!token.isEmpty() && token.chars().noneMatch(c -> Character.isWhitespace(c) || c == '"' || c == '\'')) {
            return token;
        }
        StringBuilder result = new StringBuilder("\"");
        int slashes = 0;
        for (char c : token.toCharArray()) {
            if (c == '\\') {
                slashes++;
                continue;
            }
            result.append("\\".repeat(c == '"' ? slashes * 2 + 1 : slashes));
            result.append(c);
            slashes = 0;
        }
        return result.append("\\".repeat(slashes * 2)).append('"').toString();
    }

    public static List<String> defaultJvm(GameInstance instance, List<String> defaults) {
        List<String> result = new ArrayList<>();
        result.add("-Xms" + instance.minMemoryMb() + "M");
        result.add("-Xmx" + instance.memoryMb() + "M");
        result.addAll(defaults);
        return List.copyOf(result);
    }

    private static Integer memoryValue(String token, String prefix, int index, List<String> tokens) throws IOException {
        Matcher joined = Pattern.compile(prefix + "([0-9]+)([mMgG])?").matcher(token);
        if (joined.matches()) {
            return parseMegabytes(joined.group(1), joined.group(2), token);
        }
        if (!token.equals(prefix)) {
            throw new IOException("无效的内存参数: " + token);
        }
        if (index + 1 >= tokens.size()) {
            throw new IOException(prefix + " 缺少内存数值");
        }
        String value = tokens.get(index + 1);
        Matcher separate = Pattern.compile("([0-9]+)([mMgG])?").matcher(value);
        if (!separate.matches()) {
            throw new IOException("无效的内存参数: " + value);
        }
        return parseMegabytes(separate.group(1), separate.group(2), value);
    }

    private static int parseMegabytes(String digits, String unit, String token) throws IOException {
        long value;
        try {
            value = Long.parseLong(digits);
        } catch (NumberFormatException ex) {
            throw new IOException("无效的内存参数: " + token);
        }
        if (unit != null && (unit.equals("g") || unit.equals("G"))) {
            if (value > 65536 / 1024) {
                throw new IOException("内存参数超出 65536 MB 上限: " + token);
            }
            value *= 1024;
        }
        if (value > 65536) {
            throw new IOException("内存参数超出 65536 MB 上限: " + token);
        }
        return (int) value;
    }

    public static String substitute(String input, Map<String, String> values) throws IOException {
        Matcher matcher = PLACEHOLDER.matcher(input);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value == null) {
                throw new IOException("未支持的启动参数: " + matcher.group(1));
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static List<String> splitLegacy(String input) throws IOException {
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int index = 0; index < input.length(); index++) {
            char c = input.charAt(index);
            if (c == '\\') {
                int start = index;
                while (index < input.length() && input.charAt(index) == '\\') {
                    index++;
                }
                int count = index - start;
                char next = index < input.length() ? input.charAt(index) : 0;
                if ((next == '"' || next == '\'') && (quote == 0 || quote == next)) {
                    token.append("\\".repeat(count / 2));
                    if (count % 2 != 0) {
                        token.append(next);
                    } else {
                        quote = quote == 0 ? next : 0;
                    }
                } else {
                    token.append("\\".repeat(count));
                    index--;
                }
                started = true;
                continue;
            }
            if ((c == '\"' || c == '\'') && (quote == 0 || quote == c)) {
                quote = quote == 0 ? c : 0;
                started = true;
            } else if (Character.isWhitespace(c) && quote == 0) {
                if (started) {
                    result.add(token.toString());
                    token.setLength(0);
                    started = false;
                }
            } else {
                token.append(c);
                started = true;
            }
        }
        if (quote != 0) {
            throw new IOException("旧版启动参数存在未闭合引号");
        }
        if (started) {
            result.add(token.toString());
        }
        return result;
    }
}
