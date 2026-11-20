package dev.sifr.forge;

import java.nio.file.*;
import java.util.*;

/** sifr-forge command line; direct arguments never invoke a shell. */
public final class Main {
    private Main() {}
    public static void main(String[] args) { System.exit(run(args)); }
    static int run(String[] args) {
        try {
            if (args.length < 2) { usage(); return args.length == 1 && args[0].equals("help") ? 0 : 2; }
            String command = args[0];
            Path manifestPath = Path.of(args[1]);
            Manifest manifest = ManifestParser.parse(manifestPath);
            BuildPlan plan = BuildPlan.create(manifest);
            List<String> targets = Arrays.asList(args).subList(2, args.length);
            switch (command) {
                case "plan" -> {
                    for (Target target : plan.selection(targets)) {
                        System.out.printf("%s [%s] %s -> %s%n", target.id(), target.adapter(), String.join(" ", Adapters.build(target).argv()), target.outputs());
                    }
                }
                case "graph" -> {
                    for (Target target : plan.order()) System.out.printf("%s: %s%n", target.id(), String.join(", ", target.dependsOn()));
                }
                case "why" -> {
                    var calculator = new FingerprintCalculator(manifest);
                    for (String target : targets.isEmpty() ? manifest.targets().keySet() : targets) {
                        var explanation = calculator.explain(target);
                        System.out.printf("%s fingerprint=%s inputs=%s dependencies=%s%n", target, explanation.fingerprint(), explanation.inputTreeSha256(), explanation.dependencies());
                    }
                }
                case "run" -> {
                    var results = new ForgeRunner(new ProcessExecutor()).run(plan, targets);
                    boolean failed = false;
                    for (var result : results) {
                        System.out.printf("%s %s %s%n", result.id(), result.status(), result.detail());
                        if (result.status() == ForgeRunner.Status.FAILED || result.status() == ForgeRunner.Status.TIMEOUT || result.status() == ForgeRunner.Status.SKIPPED) failed = true;
                    }
                    return failed ? 1 : 0;
                }
                default -> { usage(); return 2; }
            }
            return 0;
        } catch (ForgeException | IllegalArgumentException e) { System.err.println("sifr-forge: " + e.getMessage()); return 2; }
    }
    private static void usage() { System.out.println("usage: sifr-forge plan|run|graph|why MANIFEST [TARGET ...]"); }
}
