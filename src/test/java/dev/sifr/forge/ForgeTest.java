package dev.sifr.forge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ForgeTest {
    @TempDir Path temp;
    private Manifest manifest(String targets) throws ForgeException { return ManifestParser.parse("{\"version\":1,\"targets\":[" + targets + "]}", temp); }
    private String target(String id, String argv, String extra) { return "{\"id\":\"" + id + "\",\"adapter\":\"exec\",\"argv\":[" + argv + "],\"outputs\":[\"out-" + id + ".txt\"]" + extra + "}"; }

    @Test void deterministicDagAndCycleDetection() throws Exception {
        Manifest m = manifest(target("b", "\"/bin/true\"", ",\"dependsOn\":[\"a\"]") + "," + target("a", "\"/bin/true\"", ""));
        assertEquals(List.of("a", "b"), BuildPlan.create(m).order().stream().map(Target::id).toList());
        Manifest cycle = ManifestParser.parse("{\"version\":1,\"targets\":[{\"id\":\"a\",\"adapter\":\"exec\",\"argv\":[\"/bin/true\"],\"dependsOn\":[\"a\"]}]}", temp);
        assertThrows(ForgeException.class, () -> BuildPlan.create(cycle));
    }

    @Test void inputContentChangesTransitiveFingerprintButMtimeDoesNot() throws Exception {
        Files.writeString(temp.resolve("in.txt"), "one");
        Manifest m = manifest(target("a", "\"/bin/true\"", ",\"inputs\":[\"in.txt\"]") + "," + target("b", "\"/bin/true\"", ",\"dependsOn\":[\"a\"]"));
        var calculator = new FingerprintCalculator(m);
        String first = calculator.fingerprint("b");
        Files.setLastModifiedTime(temp.resolve("in.txt"), java.nio.file.attribute.FileTime.fromMillis(0));
        assertEquals(first, new FingerprintCalculator(m).fingerprint("b"));
        Files.writeString(temp.resolve("in.txt"), "two");
        assertNotEquals(first, new FingerprintCalculator(m).fingerprint("b"));
    }

    @Test void realExecutionPublishesAndRestoresOnlySuccessfulOutputs() throws Exception {
        Manifest m = manifest(target("a", "\"/bin/sh\",\"-c\",\"printf first > out-a.txt\"", ""));
        var runner = new ForgeRunner(new ProcessExecutor());
        assertEquals(ForgeRunner.Status.SUCCEEDED, runner.run(BuildPlan.create(m), List.of()).getFirst().status());
        Files.writeString(temp.resolve("out-a.txt"), "modified");
        var second = runner.run(BuildPlan.create(m), List.of()).getFirst();
        assertEquals(ForgeRunner.Status.CACHE_HIT, second.status());
        assertEquals("first", Files.readString(temp.resolve("out-a.txt")));
        Files.writeString(temp.resolve("input.txt"), "changed");
        Manifest changed = manifest(target("a", "\"/bin/sh\",\"-c\",\"printf second > out-a.txt\"", ",\"inputs\":[\"input.txt\"]"));
        assertEquals(ForgeRunner.Status.SUCCEEDED, runner.run(BuildPlan.create(changed), List.of()).getFirst().status());
        assertEquals("second", Files.readString(temp.resolve("out-a.txt")));
    }

    @Test void interruptedExecutionTerminatesChildProcess() throws Exception {
        Manifest m = manifest(target("interruptible", "\"/bin/sh\",\"-c\",\"sleep 30\"", ",\"timeout\":\"30s\""));
        ProcessExecutor executor = new ProcessExecutor();
        Thread worker = new Thread(() -> {
            try {
                executor.run(m, m.targets().get("interruptible"));
                throw new AssertionError("execution unexpectedly completed");
            } catch (ForgeException expected) {
                // The interruption is the expected result; the child must not survive.
            }
        });
        worker.start();
        Thread.sleep(250);
        worker.interrupt();
        worker.join(10_000);
        assertFalse(worker.isAlive(), "interrupted worker did not finish");
        long deadline = System.nanoTime() + 10_000_000_000L;
        boolean stillRunning;
        do {
            stillRunning = ProcessHandle.allProcesses()
                    .filter(process -> process.info().command().orElse("").equals("/bin/sleep"))
                    .anyMatch(process -> process.info().commandLine().orElse("").equals("/bin/sleep 30"));
            if (!stillRunning) break;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        assertFalse(stillRunning, "sleep process survived interrupted execution");
    }

    @Test void failedAndTimedOutTargetsAreNeverCached() throws Exception {
        Manifest failure = manifest(target("bad", "\"/bin/sh\",\"-c\",\"printf incomplete > out-bad.txt; exit 7\"", ""));
        var result = new ForgeRunner(new ProcessExecutor()).run(BuildPlan.create(failure), List.of()).getFirst();
        assertEquals(ForgeRunner.Status.FAILED, result.status());
        assertEquals(7, result.exitCode());
        Manifest timeout = manifest(target("slow", "\"/bin/sleep\",\"10\"", ",\"timeout\":\"1s\""));
        assertEquals(ForgeRunner.Status.TIMEOUT, new ForgeRunner(new ProcessExecutor()).run(BuildPlan.create(timeout), List.of()).getFirst().status());
        if (Files.exists(temp.resolve(".forge/cache"))) {
            try (var paths = Files.list(temp.resolve(".forge/cache"))) {
                assertEquals(0, paths.filter(path -> path.toString().endsWith(".properties")).count());
            }
        }
    }

    @Test void parallelExecutionPreservesDependenciesAndOrder() throws Exception {
        // Four independent 400 ms targets (each producing a real declared
        // output) plus one target depending on all of them.
        var targets = new ArrayList<String>();
        for (int i = 0; i < 4; i++) {
            targets.add(String.format(
                    "{\"id\":\"slow-%d\",\"adapter\":\"exec\",\"argv\":[\"/bin/sh\",\"-c\",\"sleep 0.4; printf x > out-slow-%d.txt\"],"
                            + "\"outputs\":[\"out-slow-%d.txt\"]}", i, i, i));
        }
        targets.add("{\"id\":\"join\",\"adapter\":\"exec\",\"argv\":[\"/bin/sh\",\"-c\",\"printf j > out-join.txt\"],"
                + "\"dependsOn\":[\"slow-0\",\"slow-1\",\"slow-2\",\"slow-3\"],\"outputs\":[\"out-join.txt\"]}");
        Manifest m = manifest(String.join(",", targets));
        long parallelStart = System.nanoTime();
        var results = new ForgeRunner(new ProcessExecutor(), 4).run(BuildPlan.create(m), List.of());
        long parallelMillis = (System.nanoTime() - parallelStart) / 1_000_000;

        assertEquals(5, results.size());
        assertEquals("join", results.getLast().id());
        assertEquals(ForgeRunner.Status.SUCCEEDED, results.getLast().status());
        // Four 400 ms sleeps with one worker take >= 1.6 s; with four workers
        // the wall clock must be far below that floor.
        long sequentialFloor = 4 * 400;
        assertTrue(parallelMillis < sequentialFloor - 600,
                "wall clock " + parallelMillis + "ms should be far below the sequential floor " + sequentialFloor + "ms");
    }

    @Test void parallelFailureSkipsTransitiveDependentsAndNeverCaches() throws Exception {
        Manifest m = ManifestParser.parse("{\"version\":1,\"targets\":["
                + "{\"id\":\"ok\",\"adapter\":\"exec\",\"argv\":[\"/bin/sh\",\"-c\",\"printf ok > out-ok.txt\"],\"outputs\":[\"out-ok.txt\"]},"
                + "{\"id\":\"bad\",\"adapter\":\"exec\",\"argv\":[\"/bin/false\"],\"dependsOn\":[\"ok\"]},"
                + "{\"id\":\"leaf\",\"adapter\":\"exec\",\"argv\":[\"/bin/true\"],\"dependsOn\":[\"bad\"]}]}", temp);
        var results = new ForgeRunner(new ProcessExecutor(), 3).run(BuildPlan.create(m), List.of());
        var byId = new HashMap<String, ForgeRunner.Result>();
        results.forEach(result -> byId.put(result.id(), result));
        assertEquals(ForgeRunner.Status.SUCCEEDED, byId.get("ok").status());
        assertEquals(ForgeRunner.Status.FAILED, byId.get("bad").status());
        assertEquals(ForgeRunner.Status.SKIPPED, byId.get("leaf").status());
        // Only the successful target may be cached; failure and skip never are.
        try (var entries = Files.list(temp.resolve(".forge/cache"))) {
            assertEquals(1, entries.filter(path -> path.toString().endsWith(".properties")).count());
        }
    }

    @Test void sameFingerprintAcrossJobCountsIsDeterministic() throws Exception {
        Files.writeString(temp.resolve("in.txt"), "stable");
        Manifest m = ManifestParser.parse("{\"version\":1,\"targets\":["
                + target("a", "\"/bin/sh\",\"-c\",\"printf one > out-a.txt\"", ",\"inputs\":[\"in.txt\"]") + ','
                + target("b", "\"/bin/sh\",\"-c\",\"printf two > out-b.txt\"", ",\"inputs\":[\"in.txt\"]") + ','
                + target("c", "\"/bin/sh\",\"-c\",\"printf three > out-c.txt\"", ",\"dependsOn\":[\"a\",\"b\"]") + "]}", temp);
        var single = new ForgeRunner(new ProcessExecutor(), 1).run(BuildPlan.create(m), List.of());
        // The second run shares this cache root, so a SUCCEEDED target is
        // observed as CACHE_HIT; both statuses mean "not executed".
        var many = new ForgeRunner(new ProcessExecutor(), 4).run(BuildPlan.create(m), List.of());
        for (int i = 0; i < single.size(); i++) {
            assertEquals(single.get(i).status(), ForgeRunner.Status.SUCCEEDED, "index " + i);
            assertEquals(many.get(i).status(), ForgeRunner.Status.CACHE_HIT, "index " + i);
            assertEquals(single.get(i).fingerprint(), many.get(i).fingerprint(), "fingerprint order at index " + i);
        }
    }

    @Test void corruptCacheIsReportedRatherThanTrusted() throws Exception {
        Manifest m = manifest(target("a", "\"/bin/sh\",\"-c\",\"printf value > out-a.txt\"", ""));
        var runner = new ForgeRunner(new ProcessExecutor());
        runner.run(BuildPlan.create(m), List.of());
        try (var paths = Files.list(temp.resolve(".forge/cache/blobs"))) {
            for (Path path : paths.toList()) Files.writeString(path, "corrupt");
        }
        assertEquals(ForgeRunner.Status.FAILED, runner.run(BuildPlan.create(m), List.of()).getFirst().status());
    }

    /**
     * A target-local setup failure (here: an executable that does not exist,
     * the normal shape of a missing toolchain) must fail only that target.
     * Before the fix the ForgeException escaped the worker, aborted the whole
     * run with exit code 2, discarded the independent sibling and never
     * reported the dependent as SKIPPED.
     */
    @Test void unspawnableExecutableFailsOnlyThatTargetAndKeepsSiblings() throws Exception {
        Manifest m = ManifestParser.parse("{\"version\":1,\"targets\":["
                + "{\"id\":\"missing-toolchain\",\"adapter\":\"exec\",\"argv\":[\"/nonexistent/compiler-xyz\"],\"outputs\":[]},"
                + "{\"id\":\"sibling\",\"adapter\":\"exec\",\"argv\":[\"/bin/sh\",\"-c\",\"printf s > out-sibling.txt\"],\"outputs\":[\"out-sibling.txt\"]},"
                + "{\"id\":\"dependent\",\"adapter\":\"exec\",\"argv\":[\"/bin/sh\",\"-c\",\"printf d > out-dependent.txt\"],\"dependsOn\":[\"missing-toolchain\"],\"outputs\":[\"out-dependent.txt\"]}]}", temp);
        var results = new ForgeRunner(new ProcessExecutor(), 3).run(BuildPlan.create(m), List.of());
        var byId = new HashMap<String, ForgeRunner.Result>();
        results.forEach(result -> byId.put(result.id(), result));

        assertEquals(ForgeRunner.Status.FAILED, byId.get("missing-toolchain").status());
        assertTrue(byId.get("missing-toolchain").detail().contains("compiler-xyz"),
                "the failure detail must name the executable: " + byId.get("missing-toolchain").detail());
        assertEquals(ForgeRunner.Status.SUCCEEDED, byId.get("sibling").status(),
                "an unrelated target must still run when another target cannot start");
        assertEquals(ForgeRunner.Status.SKIPPED, byId.get("dependent").status());
        assertTrue(Files.isRegularFile(temp.resolve("out-sibling.txt")), "the sibling's output must exist");
        assertFalse(Files.exists(temp.resolve("out-dependent.txt")), "a skipped target must not run");
    }

    /** A declared input that does not exist is target-local too, not a whole-build abort. */
    @Test void missingDeclaredInputFailsOnlyThatTarget() throws Exception {
        Manifest m = ManifestParser.parse("{\"version\":1,\"targets\":["
                + "{\"id\":\"needs-input\",\"adapter\":\"exec\",\"argv\":[\"/bin/true\"],\"inputs\":[\"absent.txt\"]},"
                + "{\"id\":\"independent\",\"adapter\":\"exec\",\"argv\":[\"/bin/sh\",\"-c\",\"printf i > out-independent.txt\"],\"outputs\":[\"out-independent.txt\"]}]}", temp);
        var results = new ForgeRunner(new ProcessExecutor(), 2).run(BuildPlan.create(m), List.of());
        var byId = new HashMap<String, ForgeRunner.Result>();
        results.forEach(result -> byId.put(result.id(), result));
        assertEquals(ForgeRunner.Status.FAILED, byId.get("needs-input").status());
        assertEquals(ForgeRunner.Status.SUCCEEDED, byId.get("independent").status());
    }

    /**
     * Two outputs with identical bytes share one content-addressed blob, so a
     * blob cannot carry permission bits. Restoring from cache used to copy the
     * bytes and lose the executable bit, which broke any later target that
     * executes the restored file.
     */
    @Test void cacheRestorePreservesExecutablePermission() throws Exception {
        Manifest m = ManifestParser.parse("{\"version\":1,\"targets\":["
                + "{\"id\":\"script\",\"adapter\":\"exec\",\"argv\":[\"/bin/sh\",\"-c\",\"printf '#!/bin/sh\\\\necho hi\\\\n' > out-script\"],\"outputs\":[\"out-script\"]},"
                + "{\"id\":\"executable\",\"adapter\":\"exec\",\"argv\":[\"/bin/sh\",\"-c\",\"cp out-script out-executable && chmod 755 out-executable\"],\"dependsOn\":[\"script\"],\"outputs\":[\"out-executable\"]}]}", temp);
        var runner = new ForgeRunner(new ProcessExecutor(), 2);
        runner.run(BuildPlan.create(m), List.of());
        Path executable = temp.resolve("out-executable");
        assertTrue(Files.isExecutable(executable), "cold build should produce an executable");
        // Identical content must collapse to a single blob, which is exactly why
        // the mode has to live in the entry rather than on the blob.
        try (var blobs = Files.list(temp.resolve(".forge/cache/blobs"))) {
            assertEquals(1, blobs.count(), "identical outputs should share one blob");
        }
        Files.delete(executable);
        Files.delete(temp.resolve("out-script"));

        var warm = runner.run(BuildPlan.create(m), List.of());
        assertEquals(ForgeRunner.Status.CACHE_HIT, warm.get(1).status());
        assertTrue(Files.isExecutable(executable), "cache restore must preserve the executable bit");
        assertFalse(Files.isExecutable(temp.resolve("out-script")),
                "the non-executable output must not inherit the executable's mode");
    }
}
