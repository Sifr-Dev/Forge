package dev.sifr.forge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/**
 * Success-only local output cache with content-verified restore.
 *
 * <p>Blobs are content addressed by SHA-256 and are therefore shared by any two
 * outputs with identical bytes. POSIX permission bits are <em>not</em> part of
 * the content hash, so the entry records each output's mode separately; without
 * that record a restored executable silently becomes non-executable and a
 * later target fails with a misleading "permission denied". Entries written
 * before this metadata existed carry no {@code format} marker and are treated
 * as a miss, so they are rebuilt instead of restored with wrong permissions.
 */
public final class LocalCache {
    /** Cache entry layout version; bumped when restore fidelity changes. */
    private static final String FORMAT = "2";
    /** Recorded when the filesystem cannot express POSIX permissions. */
    private static final String NO_MODE = "n/a";
    private final Path root;
    public LocalCache(Path projectRoot) { root = projectRoot.resolve(".forge/cache"); }
    public record Entry(String fingerprint, Map<String, String> outputs, Map<String, String> modes) {}
    public enum Status { HIT, MISS, CORRUPT }
    public record Lookup(Status status, Entry entry) {}

    public Lookup lookup(Manifest manifest, Target target, String fingerprint) throws ForgeException {
        Path file = root.resolve(fingerprint + ".properties");
        if (!Files.exists(file)) return new Lookup(Status.MISS, null);
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        catch (IOException e) { return new Lookup(Status.CORRUPT, null); }
        if (!fingerprint.equals(properties.getProperty("fingerprint"))) return new Lookup(Status.CORRUPT, null);
        // A legacy entry restored the wrong permissions; rebuild it instead.
        if (!FORMAT.equals(properties.getProperty("format"))) return new Lookup(Status.MISS, null);
        Map<String, String> outputs = new HashMap<>();
        Map<String, String> modes = new HashMap<>();
        for (Path output : target.outputs()) {
            String hash = properties.getProperty("output." + output);
            String mode = properties.getProperty("mode." + output);
            if (hash == null || mode == null) return new Lookup(Status.CORRUPT, null);
            Path cached = root.resolve("blobs").resolve(hash);
            if (!Files.isRegularFile(cached) || !Hashing.file(cached).sha256().equals(hash)) return new Lookup(Status.CORRUPT, null);
            outputs.put(output.toString(), hash);
            modes.put(output.toString(), mode);
        }
        return new Lookup(Status.HIT, new Entry(fingerprint, Map.copyOf(outputs), Map.copyOf(modes)));
    }

    public void restore(Manifest manifest, Target target, Entry entry) throws ForgeException {
        for (var item : entry.outputs().entrySet()) {
            Path output = resolve(manifest, item.getKey());
            if (!Files.isRegularFile(root.resolve("blobs").resolve(item.getValue()))) throw new ForgeException("cache blob missing");
            try {
                Files.createDirectories(output.toAbsolutePath().getParent());
                Files.copy(root.resolve("blobs").resolve(item.getValue()), output, StandardCopyOption.REPLACE_EXISTING);
                applyMode(output, entry.modes().get(item.getKey()));
            } catch (IOException e) { throw new ForgeException("cannot restore " + output, e); }
        }
    }

    /** Restores the recorded POSIX permission bits; a copy alone always yields the default mode. */
    private static void applyMode(Path output, String mode) throws ForgeException {
        if (mode == null || mode.equals(NO_MODE)) return;
        try {
            Files.setPosixFilePermissions(output, PosixFilePermissions.fromString(mode));
        } catch (UnsupportedOperationException e) {
            // The recording filesystem supported POSIX modes but this one does
            // not; nothing meaningful can be restored, so leave the copy alone.
        } catch (IOException e) { throw new ForgeException("cannot restore permissions of " + output, e); }
    }

    /** Renders POSIX bits as {@code rwxr-xr-x}, or {@link #NO_MODE} when unavailable. */
    private static String readMode(Path output) throws ForgeException {
        try {
            return PosixFilePermissions.toString(Files.getPosixFilePermissions(output));
        } catch (UnsupportedOperationException e) {
            return NO_MODE;
        } catch (IOException e) { throw new ForgeException("cannot read permissions of " + output, e); }
    }

    public void publish(Manifest manifest, Target target, String fingerprint) throws ForgeException {
        Properties properties = new Properties();
        properties.setProperty("fingerprint", fingerprint);
        properties.setProperty("format", FORMAT);
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
                        // Parallel workers may publish the same content hash at once.
                        Files.move(temporary, blob, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } finally { Files.deleteIfExists(temporary); }
                }
                outputs.put(declared.toString(), hash);
                properties.setProperty("output." + declared, hash);
                // The blob is shared with any identical content, so a mode that
                // lived on the blob would leak between outputs. Record it per entry.
                properties.setProperty("mode." + declared, readMode(output));
            } catch (IOException e) { throw new ForgeException("cannot cache output " + output, e); }
        }
        try {
            Files.createDirectories(root);
            Path temporary = root.resolve(fingerprint + ".tmp-" + UUID.randomUUID());
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { properties.store(writer, "Forge successful output record"); }
            Files.move(temporary, root.resolve(fingerprint + ".properties"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { throw new ForgeException("cannot publish cache entry", e); }
    }

    private Path resolve(Manifest manifest, String declared) throws ForgeException {
        Path path = manifest.projectRoot().resolve(declared).normalize();
        if (!path.startsWith(manifest.projectRoot())) throw new ForgeException("output escapes project: " + declared);
        return path;
    }
}
