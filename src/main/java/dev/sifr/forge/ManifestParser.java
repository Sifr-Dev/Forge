package dev.sifr.forge;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict manifest validation with field-aware diagnostics. */
public final class ManifestParser {
    public static final Set<String> ADAPTERS = Set.of("exec", "cmake", "ninja", "cargo", "zig", "go",
            "gradle", "maven", "dotnet", "uv", "node", "dart");
    private static final Set<String> ROOT_FIELDS = Set.of("version", "targets");
    private static final Set<String> TARGET_FIELDS = Set.of("id", "adapter", "argv", "cwd", "dependsOn",
            "inputs", "outputs", "timeout", "env", "envAllowlist");

    private ManifestParser() {
    }

    public static Manifest parse(Path path) throws ForgeException {
        Path absolute = path.toAbsolutePath().normalize();
        String source;
        try {
            source = Files.readString(absolute, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ForgeException("cannot read manifest " + absolute + ": " + e.getMessage(), e);
        }
        return parse(source, absolute.getParent());
    }

    public static Manifest parse(String source, Path root) throws ForgeException {
        Object value = Json.parse(source);
        Map<String, Object> object = object(value, "manifest");
        fields(object, ROOT_FIELDS, "manifest");
        if (!object.containsKey("version") || !object.containsKey("targets")) {
            throw new ForgeException("manifest requires version and targets");
        }
        if (!object.get("version").equals(BigDecimal.ONE)) {
            throw new ForgeException("manifest.version must be the number 1");
        }
        List<Object> rawTargets = array(object.get("targets"), "manifest.targets");
        var targets = new LinkedHashMap<String, Target>();
        for (int i = 0; i < rawTargets.size(); i++) {
            String location = "manifest.targets[" + i + "]";
            Map<String, Object> raw = object(rawTargets.get(i), location);
            fields(raw, TARGET_FIELDS, location);
            require(raw, location, "id", "adapter", "argv");
            String id = string(raw.get("id"), location + ".id");
            String label = "target " + id;
            try {
                String adapter = string(raw.get("adapter"), label + ".adapter");
                if (!ADAPTERS.contains(adapter)) {
                    throw new ForgeException(label + ": unsupported adapter '" + adapter + "'");
                }
                List<String> argv = strings(raw.get("argv"), label + ".argv");
                Path cwd = path(raw.containsKey("cwd") ? string(raw.get("cwd"), label + ".cwd") : ".");
                List<String> dependsOn = raw.containsKey("dependsOn")
                        ? strings(raw.get("dependsOn"), label + ".dependsOn") : List.of();
                List<Path> inputs = paths(raw, "inputs", label);
                List<Path> outputs = paths(raw, "outputs", label);
                Duration timeout = raw.containsKey("timeout")
                        ? duration(string(raw.get("timeout"), label + ".timeout"), label) : Duration.ofMinutes(10);
                Map<String, String> env = environment(raw.getOrDefault("env", Map.of()), label + ".env");
                Set<String> allowlist = new LinkedHashSet<>(List.of("PATH"));
                if (raw.containsKey("envAllowlist")) {
                    allowlist.addAll(strings(raw.get("envAllowlist"), label + ".envAllowlist"));
                }
                Target target = new Target(id, adapter, argv, cwd, dependsOn, inputs, outputs, timeout, env, allowlist);
                if (targets.putIfAbsent(id, target) != null) {
                    throw new ForgeException("duplicate target id: " + id);
                }
            } catch (IllegalArgumentException e) {
                throw new ForgeException(label + ": " + e.getMessage(), e);
            }
        }
        try {
            return new Manifest(root, targets);
        } catch (IllegalArgumentException e) {
            throw new ForgeException(e.getMessage(), e);
        }
    }

    private static List<Path> paths(Map<String, Object> raw, String field, String label) throws ForgeException {
        if (!raw.containsKey(field)) {
            return List.of();
        }
        var result = new ArrayList<Path>();
        for (String value : strings(raw.get(field), label + "." + field)) {
            result.add(path(value));
        }
        return result;
    }

    private static Duration duration(String text, String label) throws ForgeException {
        if (text.length() < 2 || !text.endsWith("s")) {
            throw new ForgeException(label + ": timeout must be seconds with an 's' suffix (for example '30s')");
        }
        try {
            long seconds = Long.parseLong(text.substring(0, text.length() - 1));
            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException | ArithmeticException e) {
            throw new ForgeException(label + ": invalid timeout '" + text + "'");
        }
    }

    private static Path path(String value) {
        try {
            return Path.of(value).normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("invalid path '" + value + "'", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> environment(Object value, String label) throws ForgeException {
        Map<String, Object> map = object(value, label);
        var result = new LinkedHashMap<String, String>();
        for (var entry : map.entrySet()) {
            result.put(entry.getKey(), string(entry.getValue(), label + "." + entry.getKey()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String label) throws ForgeException {
        if (!(value instanceof Map<?, ?>)) {
            throw new ForgeException(label + " must be an object");
        }
        return (Map<String, Object>) value;
    }

    private static List<Object> array(Object value, String label) throws ForgeException {
        if (!(value instanceof List<?> list)) {
            throw new ForgeException(label + " must be an array");
        }
        return new ArrayList<>(list);
    }

    private static String string(Object value, String label) throws ForgeException {
        if (!(value instanceof String string)) {
            throw new ForgeException(label + " must be a string");
        }
        return string;
    }

    private static List<String> strings(Object value, String label) throws ForgeException {
        List<Object> array = array(value, label);
        var result = new ArrayList<String>();
        for (int i = 0; i < array.size(); i++) {
            result.add(string(array.get(i), label + "[" + i + "]"));
        }
        return List.copyOf(result);
    }

    private static void fields(Map<String, Object> map, Set<String> allowed, String label) throws ForgeException {
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) {
                throw new ForgeException(label + ": unknown field '" + key + "'");
            }
        }
    }

    private static void require(Map<String, Object> map, String label, String... fields) throws ForgeException {
        for (String field : fields) {
            if (!map.containsKey(field)) {
                throw new ForgeException(label + ": required field '" + field + "' is missing");
            }
        }
    }
}
