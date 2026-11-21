package dev.sifr.forge;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable target definition. All paths are relative to the manifest directory. */
public record Target(
        String id,
        String adapter,
        List<String> argv,
        Path cwd,
        List<String> dependsOn,
        List<Path> inputs,
        List<Path> outputs,
        Duration timeout,
        Map<String, String> env,
        Set<String> envAllowlist) {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern ENV = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public Target {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("target id must match [A-Za-z0-9][A-Za-z0-9._-]{0,127}");
        }
        requireText(adapter, "adapter");
        if (argv == null || argv.isEmpty()) {
            throw new IllegalArgumentException("target " + id + ": argv must not be empty");
        }
        argv = List.copyOf(argv);
        argv.forEach(arg -> requireText(arg, "target " + id + " argv element"));
        if (cwd == null || cwd.isAbsolute() || cwd.normalize().startsWith("..")) {
            throw new IllegalArgumentException("target " + id + ": cwd must stay within the project");
        }
        cwd = cwd.normalize();
        dependsOn = List.copyOf(dependsOn);
        if (dependsOn.stream().anyMatch(d -> !ID.matcher(d).matches())) {
            throw new IllegalArgumentException("target " + id + ": invalid dependency id");
        }
        if (Set.copyOf(dependsOn).size() != dependsOn.size()) {
            throw new IllegalArgumentException("target " + id + ": duplicate dependency");
        }
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        for (Path path : inputs) {
            requireRelative(path, id, "input");
        }
        for (Path path : outputs) {
            requireRelative(path, id, "output");
        }
        if (Set.copyOf(outputs).size() != outputs.size()) {
            throw new IllegalArgumentException("target " + id + ": duplicate output");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("target " + id + ": timeout must be between 1 ms and 1 day");
        }
        env = Map.copyOf(env);
        envAllowlist = Set.copyOf(envAllowlist);
        for (String name : env.keySet()) {
            requireEnvironmentName(name, id);
            requireText(env.get(name), "environment value for " + name);
        }
        for (String name : envAllowlist) {
            requireEnvironmentName(name, id);
        }
    }

    private static void requireEnvironmentName(String name, String id) {
        if (!ENV.matcher(name).matches()) {
            throw new IllegalArgumentException("target " + id + ": invalid environment name " + name);
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isEmpty() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(label + " must be non-empty and contain no NUL");
        }
    }

    private static void requireRelative(Path path, String id, String kind) {
        if (path == null || path.isAbsolute() || path.normalize().startsWith("..")) {
            throw new IllegalArgumentException("target " + id + ": " + kind
                    + " must be a relative path within the project: " + path);
        }
    }
}
