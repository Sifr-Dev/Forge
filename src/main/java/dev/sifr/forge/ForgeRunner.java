package dev.sifr.forge;

import java.util.*;
import java.util.concurrent.*;

/**
 * Execution engine with deterministic result ordering and optional bounded
 * parallelism.
 *
 * <p>Indegree-driven scheduling: a target becomes eligible when all of its
 * dependencies have succeeded. Ready targets are chosen in lexicographic id
 * order, so the set of started targets and the emitted logs are deterministic
 * for a given job count even though completion order is not. Results are
 * always returned in plan order.
 *
 * <p>Parallelism bounds concurrent child processes, not threads. Each worker is
 * a platform thread because the work is dominated by process supervision and
 * blocking stream drains.
 */
public final class ForgeRunner {
    public enum Status { SUCCEEDED, CACHE_HIT, FAILED, TIMEOUT, SKIPPED }
    public record Result(String id, Status status, String fingerprint, int exitCode, String detail) {}

    private final ProcessExecutor executor;
    private final int jobs;

    public ForgeRunner(ProcessExecutor executor) { this(executor, 1); }

    public ForgeRunner(ProcessExecutor executor, int jobs) {
        if (jobs < 1) throw new IllegalArgumentException("jobs must be >= 1");
        this.executor = executor;
        this.jobs = jobs;
    }

    /** Sequential execution; equivalent to {@code run(plan, requested, 1)}. */
    public List<Result> run(BuildPlan plan, List<String> requested) throws ForgeException {
        return run(plan, requested, jobs);
    }

    public List<Result> run(BuildPlan plan, List<String> requested, int parallelism) throws ForgeException {
        if (parallelism < 1) throw new IllegalArgumentException("parallelism must be >= 1");
        List<Target> order = plan.selection(requested);
        Map<String, Result> completed = new HashMap<>();
        List<String> requestedIds = order.stream().map(Target::id).toList();

        Map<String, Integer> remaining = new TreeMap<>();
        Map<String, List<String>> dependents = new TreeMap<>();
        for (Target target : order) {
            remaining.put(target.id(), target.dependsOn().stream().filter(requestedIds::contains).count() > 0
                    ? (int) target.dependsOn().stream().filter(requestedIds::contains).count() : 0);
            dependents.put(target.id(), new ArrayList<>());
        }
        for (Target target : order) {
            for (String dependency : target.dependsOn()) {
                if (dependents.containsKey(dependency)) dependents.get(dependency).add(target.id());
            }
        }

        var ready = new PriorityQueue<String>();
        remaining.forEach((id, degree) -> { if (degree == 0) ready.add(id); });

        ExecutorService pool = Executors.newFixedThreadPool(parallelism, runnable -> {
            Thread thread = new Thread(runnable, "forge-worker");
            thread.setDaemon(true);
            return thread;
        });
        try {
            int inFlight = 0;
            CompletionService<Result> completion = new ExecutorCompletionService<>(pool);
            while (true) {
                // Fill the window from the deterministic ready set.
                while (inFlight < parallelism && !ready.isEmpty()) {
                    String id = ready.remove();
                    Target target = plan.manifest().targets().get(id);
                    if (blockedByFailure(target, completed)) {
                        Result skipped = new Result(id, Status.SKIPPED, null, -1, "dependency failed");
                        completed.put(id, skipped);
                        release(dependents, remaining, ready, id);
                        continue;
                    }
                    inFlight++;
                    completion.submit(() -> executeOne(plan, target));
                }
                if (inFlight == 0) {
                    if (ready.isEmpty()) break;
                    continue;
                }
                Result result;
                try {
                    result = completion.take().get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ForgeException("interrupted while waiting for a target");
                } catch (ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    throw cause instanceof ForgeException forge ? forge
                            : new ForgeException("target execution failed: " + cause, cause);
                }
                inFlight--;
                completed.put(result.id(), result);
                release(dependents, remaining, ready, result.id());
            }
        } finally {
            pool.shutdownNow();
        }

        var results = new ArrayList<Result>(order.size());
        for (Target target : order) results.add(completed.get(target.id()));
        return List.copyOf(results);
    }

    private static boolean blockedByFailure(Target target, Map<String, Result> completed) {
        for (String dependency : target.dependsOn()) {
            Result result = completed.get(dependency);
            if (result != null && (result.status() == Status.FAILED || result.status() == Status.TIMEOUT
                    || result.status() == Status.SKIPPED)) return true;
        }
        return false;
    }

    private static void release(Map<String, List<String>> dependents, Map<String, Integer> remaining,
                                PriorityQueue<String> ready, String id) {
        for (String dependent : dependents.getOrDefault(id, List.of())) {
            int left = remaining.merge(dependent, -1, Integer::sum);
            if (left == 0) ready.add(dependent);
        }
    }

    /**
     * Runs one target in isolation. A {@link ForgeException} here is a
     * target-local failure (unspawnable executable, missing declared input,
     * unusable output path) and must not abort the build: it becomes a
     * {@code FAILED} result so dependents are skipped and unrelated targets in
     * flight keep running. Only cancellation (the worker thread is already
     * interrupted) still aborts. An unexpected runtime exception is an engine
     * defect and still aborts.
     */
    private Result executeOne(BuildPlan plan, Target target) throws ForgeException {
        String fingerprint = null;
        try {
            var calculator = new FingerprintCalculator(plan.manifest());
            var cache = new LocalCache(plan.manifest().projectRoot());
            fingerprint = calculator.fingerprint(target.id());
            LocalCache.Lookup lookup = cache.lookup(plan.manifest(), target, fingerprint);
            if (lookup.status() == LocalCache.Status.HIT) {
                cache.restore(plan.manifest(), target, lookup.entry());
                return new Result(target.id(), Status.CACHE_HIT, fingerprint, 0, "restored verified outputs");
            }
            if (lookup.status() == LocalCache.Status.CORRUPT) {
                return new Result(target.id(), Status.FAILED, fingerprint, -1, "cache integrity check failed");
            }
            var execution = executor.run(plan.manifest(), target);
            if (execution.timedOut()) return new Result(target.id(), Status.TIMEOUT, fingerprint, -1, execution.stderr());
            if (execution.exitCode() != 0) {
                return new Result(target.id(), Status.FAILED, fingerprint, execution.exitCode(), execution.stderr());
            }
            cache.publish(plan.manifest(), target, fingerprint);
            return new Result(target.id(), Status.SUCCEEDED, fingerprint, 0, execution.stdout());
        } catch (ForgeException failure) {
            // Cancellation is not a target failure: it must stop the build.
            if (Thread.currentThread().isInterrupted()) throw failure;
            String detail = failure.getMessage() == null ? "target failed" : failure.getMessage();
            return new Result(target.id(), Status.FAILED, fingerprint, -1, detail);
        }
    }
}
