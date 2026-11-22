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
}
