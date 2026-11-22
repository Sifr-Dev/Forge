package dev.sifr.forge;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Coarse command builders. Forge invokes the named installed tool; it does not emulate any tool's semantics.
 * Manifest argv excludes the tool executable and, where appropriate, the fixed build subcommand.
 */
public final class Adapters {
    private Adapters() {
    }

    public static Command build(Target target) {
        List<String> args = target.argv();
        var result = new ArrayList<String>();
        switch (target.adapter()) {
            case "exec" -> result.addAll(args);
            // `maven` is the ecosystem name; the executable is `mvn` on every
            // common distribution, so the adapter must not emit the former.
            case "maven" -> {
                result.add("mvn");
                result.addAll(args);
            }
            case "cmake", "ninja", "zig", "gradle", "dotnet", "uv", "node", "dart" -> {
                result.add(target.adapter());
                result.addAll(args);
            }
            case "cargo" -> fixed("cargo", "build", args, result);
            case "go" -> fixed("go", "build", args, result);
            default -> throw new IllegalArgumentException("unsupported adapter: " + target.adapter());
        }
        return new Command(result);
    }

    private static final Set<String> TOOLCHAIN_HOME = Set.of(
            "HOME", "XDG_CACHE_HOME", "XDG_CONFIG_HOME", "TMPDIR");

    /**
     * Environment a real toolchain needs and that {@code PATH} alone does not
     * provide. Found by adapter verification: Go and Zig fail without a
     * resolvable user cache directory, and Dart/.NET write to the home
     * directory. These names are passed through and, like every allowlisted
     * name, their values participate in the fingerprint.
     */
    public static Set<String> requiredEnvironment(String adapter) {
        return switch (adapter) {
            case "cargo", "zig", "go", "maven", "gradle", "dotnet", "dart", "uv", "node", "cmake", "ninja" -> TOOLCHAIN_HOME;
            default -> Set.of();
        };
    }

    private static void fixed(String executable, String subcommand, List<String> args, List<String> result) {
        result.add(executable);
        result.add(subcommand);
        result.addAll(args);
    }
}
