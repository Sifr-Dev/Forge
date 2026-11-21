package dev.sifr.forge;

import java.util.ArrayList;
import java.util.List;

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
            case "cmake", "ninja", "zig", "gradle", "maven", "dotnet", "uv", "node", "dart" -> {
                result.add(target.adapter());
                result.addAll(args);
            }
            case "cargo" -> fixed("cargo", "build", args, result);
            case "go" -> fixed("go", "build", args, result);
            default -> throw new IllegalArgumentException("unsupported adapter: " + target.adapter());
        }
        return new Command(result);
    }

    private static void fixed(String executable, String subcommand, List<String> args, List<String> result) {
        result.add(executable);
        result.add(subcommand);
        result.addAll(args);
    }
}
