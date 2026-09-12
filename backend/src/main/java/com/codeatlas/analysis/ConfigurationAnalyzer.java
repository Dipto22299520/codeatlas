package com.codeatlas.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import org.yaml.snakeyaml.Yaml;

/**
 * Reads configuration files into flat key/value observations.
 *
 * Whole files are never copied wholesale: the refresh pipeline keeps only
 * allowlisted keys (README section 9, BR-41). Secret-like keys are rejected
 * here as a defence in depth supplementing the allowlist (CC-8, BR-42).
 */
public class ConfigurationAnalyzer {

    /** Key fragments that must never be snapshotted, whatever the allowlist says. */
    private static final List<String> SECRET_FRAGMENTS = List.of(
            "password", "passwd", "secret", "token", "apikey", "api-key", "api_key",
            "credential", "private-key", "privatekey", "access-key", "accesskey",
            "authorization", "auth-token", "client-secret");

    public boolean isSecretLike(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return SECRET_FRAGMENTS.stream().anyMatch(normalized::contains);
    }

    /** Flattens a YAML or properties file into dotted keys with line numbers. */
    public List<ConfigValue> read(String assetId, Path file, String relativePath) {
        List<ConfigValue> values = new ArrayList<>();
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, String> flat = relativePath.endsWith(".properties")
                    ? readProperties(content)
                    : readYaml(content);

            for (Map.Entry<String, String> entry : flat.entrySet()) {
                if (isSecretLike(entry.getKey())) {
                    continue;
                }
                int line = findLine(content, entry.getKey());
                values.add(new ConfigValue(assetId, entry.getKey(), entry.getValue(),
                        inferType(entry.getValue()), relativePath, line, null));
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        return values;
    }

    private Map<String, String> readProperties(String content) throws IOException {
        Properties properties = new Properties();
        properties.load(new java.io.StringReader(content));
        Map<String, String> flat = new LinkedHashMap<>();
        properties.stringPropertyNames().stream().sorted()
                .forEach(name -> flat.put(name, properties.getProperty(name)));
        return flat;
    }

    private Map<String, String> readYaml(String content) {
        Object loaded = new Yaml().load(content);
        Map<String, String> flat = new LinkedHashMap<>();
        flatten("", loaded, flat);
        return flat;
    }

    @SuppressWarnings("unchecked")
    private void flatten(String prefix, Object node, Map<String, String> out) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                String composed = prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key;
                flatten(composed, value, out);
            });
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                flatten(prefix + "[" + i + "]", list.get(i), out);
            }
        } else if (node != null) {
            out.put(prefix, String.valueOf(node));
        }
    }

    /** Locates the line declaring the final segment of a dotted key. */
    private int findLine(String content, String key) {
        String leaf = key.contains(".") ? key.substring(key.lastIndexOf('.') + 1) : key;
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith(leaf + ":") || trimmed.startsWith(key + "=")
                    || trimmed.startsWith(leaf + " :")) {
                return i + 1;
            }
        }
        return 1;
    }

    private String inferType(String value) {
        if (value.matches("-?\\d+(\\.\\d+)?")) {
            return "number";
        }
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return "boolean";
        }
        if (value.matches("\\d+[smhd]")) {
            return "duration";
        }
        return "string";
    }
}
