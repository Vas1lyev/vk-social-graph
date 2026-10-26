package ru.vasilyev.mirea.vkgraph.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class EnvironmentConfig {
    public String get(Path envFile, String key) throws IOException {
        String environment = normalize(System.getenv(key));
        if (environment != null) {
            return environment;
        }
        if (!Files.isRegularFile(envFile)) {
            return null;
        }
        for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
            String value = line.strip();
            if (value.isBlank() || value.startsWith("#")) {
                continue;
            }
            int separator = value.indexOf('=');
            if (separator >= 0 && value.substring(0, separator).strip().equals(key)) {
                return normalize(value.substring(separator + 1));
            }
        }
        return null;
    }

    public int getPositiveInt(Path envFile, String key, int fallback) throws IOException {
        String value = get(envFile, key);
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(key + " должен быть положительным целым числом, получено: " + value);
        }
    }

    public int getPositiveIntOrUnlimited(Path envFile, String key, int fallback) throws IOException {
        String value = get(envFile, key);
        if (value == null) {
            return fallback;
        }
        if (value.equalsIgnoreCase("all")) {
            return Integer.MAX_VALUE;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(key
                    + " должен быть положительным целым числом или all, получено: " + value);
        }
    }

    static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String result = value.strip();
        if (result.length() >= 2 && ((result.startsWith("\"") && result.endsWith("\""))
                || (result.startsWith("'") && result.endsWith("'")))) {
            result = result.substring(1, result.length() - 1).strip();
        }
        return result.isBlank() ? null : result;
    }
}
