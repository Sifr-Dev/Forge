package dev.sifr.forge;

import java.util.*;

/** Deterministic sequential execution with success-only cache publication. */
public final class ForgeRunner {
    public enum Status { SUCCEEDED, CACHE_HIT, FAILED, TIMEOUT, SKIPPED }
    public record Result(String id, Status status, String fingerprint, int exitCode, String detail) {}
    private final ProcessExecutor executor;
    public ForgeRunner(ProcessExecutor executor) { this.executor = executor; }

    public List<Result> run(BuildPlan plan, List<String> requested) throws ForgeException {
        var calculator = new FingerprintCalculator(plan.manifest());
        var cache = new LocalCache(plan.manifest().projectRoot());
        var results = new ArrayList<Result>();
        Set<String> failed = new HashSet<>();
        for (Target target : plan.selection(requested)) {
            if (target.dependsOn().stream().anyMatch(failed::contains)) {
                results.add(new Result(target.id(), Status.SKIPPED, null, -1, "dependency failed"));
                failed.add(target.id()); continue;
            }
            String fingerprint = calculator.fingerprint(target.id());
            LocalCache.Lookup lookup = cache.lookup(plan.manifest(), target, fingerprint);
            if (lookup.status() == LocalCache.Status.HIT) {
                cache.restore(plan.manifest(), target, lookup.entry());
                results.add(new Result(target.id(), Status.CACHE_HIT, fingerprint, 0, "restored verified outputs")); continue;
            }
            if (lookup.status() == LocalCache.Status.CORRUPT) {
                results.add(new Result(target.id(), Status.FAILED, fingerprint, -1, "cache integrity check failed"));
                failed.add(target.id()); continue;
            }
            var execution = executor.run(plan.manifest(), target);
            if (execution.timedOut()) {
                results.add(new Result(target.id(), Status.TIMEOUT, fingerprint, -1, execution.stderr()));
                failed.add(target.id()); continue;
            }
            if (execution.exitCode() != 0) {
                results.add(new Result(target.id(), Status.FAILED, fingerprint, execution.exitCode(), execution.stderr()));
                failed.add(target.id()); continue;
            }
            cache.publish(plan.manifest(), target, fingerprint);
            results.add(new Result(target.id(), Status.SUCCEEDED, fingerprint, 0, execution.stdout()));
        }
        return List.copyOf(results);
    }
}
