package com.fuzzlauncher.infrastructure.mod;

import com.google.gson.*;

import java.util.*;

/** Shared JSON field conventions; no archive or format dispatch responsibilities. */
final class JsonMetadataFields {
    private JsonMetadataFields() {
    }

    static String required(JsonObject json, String key) {
        String value = string(json, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException("缺少 " + key);
        }
        return value;
    }

    static String string(JsonObject json, String key, String fallback) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    static String people(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        if (!value.isJsonArray()) {
            return value.isJsonObject() ? string(value.getAsJsonObject(), "name", "") : value.getAsString();
        }
        List<String> names = new ArrayList<>();
        for (JsonElement person : value.getAsJsonArray()) {
            if (person.isJsonObject()) {
                names.add(string(person.getAsJsonObject(), "name", ""));
            } else if (person.isJsonPrimitive()) {
                names.add(person.getAsString());
            }
        }
        return String.join(", ", names);
    }

    static void icon(JsonElement value, List<String> output) {
        if (value == null || value.isJsonNull()) {
            return;
        }
        if (value.isJsonObject()) {
            value.getAsJsonObject().entrySet().stream().sorted(Comparator.comparingInt(entry -> iconSizeOrder(entry.getKey())))
                .map(Map.Entry::getValue).filter(JsonElement::isJsonPrimitive).forEach(item -> output.add(item.getAsString()));
        } else if (value.isJsonPrimitive() && !value.getAsString().isBlank()) {
            output.add(value.getAsString());
        }
    }

    static int iconSizeOrder(String size) {
        try {
            int pixels = Integer.parseInt(size);
            if (pixels <= 0) {
                return Integer.MAX_VALUE;
            }
            return pixels >= 64 ? pixels - 64 : Integer.MAX_VALUE - pixels;
        } catch (NumberFormatException ex) {
            return Integer.MAX_VALUE;
        }
    }
}
