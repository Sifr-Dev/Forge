package dev.sifr.forge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Success-only local output cache with content-verified restore. */
public final class LocalCache {
    private final Path root;
    public LocalCache(Path projectRoot) { root = projectRoot.resolve(".forge/cache"); }
    public record Entry(String fingerprint, Map<String, String> outputs) {}
    public enum Status { HIT, MISS, CORRUPT }
    public record Lookup(Status status, Entry entry) {}

    public Lookup lookup(Manifest manifest, Target target, String fingerprint) throws ForgeException {
        Path file = root.resolve(fingerprint + ".properties");
        if (!Files.exists(file)) return new Lookup(Status.MISS, null);
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        catch (IOException e) { return new Lookup(Status.CORRUPT, null); }
        if (!fingerprint.equals(properties.getProperty("fingerprint"))) return new Lookup(Status.CORRUPT, null);
        Map<String, String> outputs = new HashMap<>();
        for (Path output : target.outputs()) {
            String hash = properties.getProperty("output." + output);
            if (hash == null) return new Lookup(Status.CORRUPT, null);
            Path cached = root.resolve("blobs").resolve(hash);
            if (!Files.isRegularFile(cached) || !Hashing.file(cached).sha256().equals(hash)) return new Lookup(Status.CORRUPT, null);
            outputs.put(output.toString(), hash);
        }
        return new Lookup(Status.HIT, new Entry(fingerprint, Map.copyOf(outputs)));
    }

    public void restore(Manifest manifest, Target target, Entry entry) throws ForgeException {
        for (var item : entry.outputs().entrySet()) {
            Path output = resolve(manifest, item.getKey());
            if (!Files.isRegularFile(root.resolve("blobs").resolve(item.getValue()))) throw new ForgeException("cache blob missing");
            try {
                Files.createDirectories(output.toAbsolutePath().getParent());
                Files.copy(root.resolve("blobs").resolve(item.getValue()), output, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) { throw new ForgeException("cannot restore " + output, e); }
        }
    }

    public void publish(Manifest manifest, Target target, String fingerprint) throws ForgeException {
        Properties properties = new Properties();
        properties.setProperty("fingerprint", fingerprint);
        Map<String, String> outputs = new LinkedHashMap<>();
        for (Path declared : target.outputs()) {
            Path output = resolve(manifest, declared.toString());
            Hashing.requireRegularFile(output, "declared output");
            String hash = Hashing.file(output).sha256();
            Path blob = root.resolve("blobs").resolve(hash);
            try {
                Files.createDirectories(blob.getParent());
                if (!Files.exists(blob)) {
                    Path temporary = root.resolve("blobs").resolve(hash + ".tmp-" + UUID.randomUUID());
                    try {
                        Files.copy(output, temporary);
                        if (!Hashing.file(temporary).sha256().equals(hash)) throw new ForgeException("cache copy failed integrity check");
                        Files.move(temporary, blob, StandardCopyOption.ATOMIC_MOVE);
                    } finally { Files.deleteIfExists(temporary); }
                }
                outputs.put(declared.toString(), hash);
                properties.setProperty("output." + declared, hash);
            } catch (IOException e) { throw new ForgeException("cannot cache output " + output, e); }
        }
        try {
            Files.createDirectories(root);
            Path temporary = root.resolve(fingerprint + ".tmp-" + UUID.randomUUID());
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { properties.store(writer, "Forge successful output record"); }
            Files.move(temporary, root.resolve(fingerprint + ".properties"), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) { throw new ForgeException("cannot publish cache entry", e); }
    }

    private Path resolve(Manifest manifest, String declared) throws ForgeException {
        Path path = manifest.projectRoot().resolve(declared).normalize();
        if (!path.startsWith(manifest.projectRoot())) throw new ForgeException("output escapes project: " + declared);
        return path;
    }
}
