package dev.sifr.forge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Length-prefixed, domain-separated SHA-256 primitives. */
public final class Hashing {
    private Hashing() {
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK does not provide SHA-256", e);
        }
    }

    public static void field(MessageDigest digest, String domain, byte[] bytes) {
        digest.update(longBytes(domain.getBytes(StandardCharsets.UTF_8).length));
        digest.update(domain.getBytes(StandardCharsets.UTF_8));
        digest.update(longBytes(bytes.length));
        digest.update(bytes);
    }

    public static void field(MessageDigest digest, String domain, String value) {
        field(digest, domain, value.getBytes(StandardCharsets.UTF_8));
    }

    public static void fields(MessageDigest digest, String domain, List<String> values) {
        field(digest, domain + ".count", longBytes(values.size()));
        for (String value : values) {
            field(digest, domain + ".item", value);
        }
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    public static FileDigest file(Path path, String displayPath) throws ForgeException {
        MessageDigest digest = sha256();
        long size = 0;
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                    size += read;
                }
            }
        } catch (IOException e) {
            throw new ForgeException("cannot read file " + path + ": " + e.getMessage(), e);
        }
        return new FileDigest(displayPath, size, hex(digest.digest()));
    }

    public static FileDigest file(Path path) throws ForgeException {
        return file(path, path.toString());
    }

    public static void requireRegularFile(Path path, String label) throws ForgeException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new ForgeException(label + " must be a regular file, not a symlink or special file: " + path);
        }
    }

    private static byte[] longBytes(long value) {
        return ByteBuffer.allocate(Long.BYTES).order(ByteOrder.BIG_ENDIAN).putLong(value).array();
    }

    public record FileDigest(String path, long size, String sha256) {
    }
}
