package dev.sifr.forge;

import java.util.List;
import java.util.Map;

/** Persisted successful result. Output bytes are content addressed by SHA-256. */
public record CacheEntry(
        String target,
        String fingerprint,
        Map<String, CachedFile> files,
        Map<String, String> dependencies,
        String inputTreeSha256,
        long exitCode,
        String logPath) {
    public CacheEntry {
        files = Map.copyOf(files);
        dependencies = Map.copyOf(dependencies);
        if (exitCode != 0) {
            throw new IllegalArgumentException("only successful results can be cached");
        }
    }

    public record CachedFile(String sha256, long size) {
    }
}
