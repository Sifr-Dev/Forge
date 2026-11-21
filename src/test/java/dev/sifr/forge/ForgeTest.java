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
