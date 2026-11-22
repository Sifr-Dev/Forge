package dev.sifr.forge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Computes a Merkle DAG: a target hash covers its recipe, input contents and dependency hashes. */
public final class FingerprintCalculator {
    public static final String DOMAIN = "sifr-forge.target.v1";
    private final Manifest manifest;
    private final Map<String, Explanation> explanations = new HashMap<>();

    public FingerprintCalculator(Manifest manifest) {
        this.manifest = manifest;
    }

    public String fingerprint(String id) throws ForgeException {
        return explain(id).fingerprint();
    }

    public Explanation explain(String id) throws ForgeException {
        Explanation known = explanations.get(id);
        if (known != null) {
            return known;
        }
        Target target = manifest.targets().get(id);
        if (target == null) {
            throw new ForgeException("unknown target '" + id + "'");
        }
        var dependencies = new TreeMap<String, String>();
        for (String dependency : target.dependsOn()) {
            dependencies.put(dependency, explain(dependency).fingerprint());
        }
        Command command = Adapters.build(target);
        var inputs = new ArrayList<Hashing.FileDigest>();
        MessageDigest inputDigest = Hashing.sha256();
        for (Path declared : target.inputs().stream().sorted(Comparator.comparing(Path::toString)).toList()) {
            Path path = resolve(declared);
            hashPath(path, declared, declared.toString(), inputDigest, inputs);
        }
        var environment = new TreeMap<String, String>();
        var allowlist = new java.util.TreeSet<>(target.envAllowlist());
        allowlist.addAll(Adapters.requiredEnvironment(target.adapter()));
        for (String name : allowlist) {
            String value = System.getenv(name);
            if (value != null) {
                environment.put(name, value);
            }
        }
        environment.putAll(target.env());

        MessageDigest digest = Hashing.sha256();
        Hashing.field(digest, DOMAIN, "target");
        Hashing.field(digest, DOMAIN + ".id", target.id());
        Hashing.field(digest, DOMAIN + ".adapter", target.adapter());
        Hashing.field(digest, DOMAIN + ".cwd", target.cwd().toString());
        Hashing.fields(digest, DOMAIN + ".argv", command.argv());
        Hashing.fields(digest, DOMAIN + ".outputs", target.outputs().stream().map(Path::toString).toList());
        Hashing.field(digest, DOMAIN + ".timeout", target.timeout().toString());
        byte[] inputTreeBytes = inputDigest.digest();
        Hashing.field(digest, DOMAIN + ".inputTree", inputTreeBytes);
        Hashing.field(digest, DOMAIN + ".environment.count", String.valueOf(environment.size()));
        environment.forEach((name, value) -> {
            Hashing.field(digest, DOMAIN + ".environment.name", name);
            Hashing.field(digest, DOMAIN + ".environment.value", value);
        });
        Hashing.field(digest, DOMAIN + ".dependencies.count", String.valueOf(dependencies.size()));
        dependencies.forEach((dependency, hash) -> {
            Hashing.field(digest, DOMAIN + ".dependency.id", dependency);
            Hashing.field(digest, DOMAIN + ".dependency.hash", hash);
        });
        String localInputHash = Hashing.hex(inputTreeBytes);
        Explanation explanation = new Explanation(id, Hashing.hex(digest.digest()), localInputHash,
                List.copyOf(inputs), Map.copyOf(dependencies), command.argv(), Map.copyOf(environment));
        explanations.put(id, explanation);
        return explanation;
    }

    private void hashPath(Path absolute, Path declared, String display, MessageDigest tree,
                          List<Hashing.FileDigest> snapshots) throws ForgeException {
        if (Files.isSymbolicLink(absolute)) {
            throw new ForgeException("symbolic links are not supported in declared inputs: " + absolute);
        }
        if (Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            Hashing.field(tree, DOMAIN + ".input.directory", normalize(display));
            List<Path> children;
            try (Stream<Path> stream = Files.list(absolute)) {
                children = stream.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
            } catch (IOException e) {
                throw new ForgeException("cannot list input directory " + absolute + ": " + e.getMessage(), e);
            }
            for (Path child : children) {
                String childDisplay = display.isEmpty() || display.equals(".")
                        ? child.getFileName().toString() : display + "/" + child.getFileName();
                hashPath(child, declared, childDisplay, tree, snapshots);
            }
        } else if (Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            Hashing.FileDigest snapshot = Hashing.file(absolute, normalize(display));
            Hashing.field(tree, DOMAIN + ".input.file.path", snapshot.path());
            Hashing.field(tree, DOMAIN + ".input.file.length", String.valueOf(snapshot.size()));
            Hashing.field(tree, DOMAIN + ".input.file.sha256", snapshot.sha256());
            snapshots.add(snapshot);
        } else {
            throw new ForgeException("declared input does not exist or is not a regular file/directory: " + absolute);
        }
    }

    private Path resolve(Path path) throws ForgeException {
        Path resolved = manifest.projectRoot().resolve(path).normalize();
        if (!resolved.startsWith(manifest.projectRoot())) {
            throw new ForgeException("path escapes the project: " + path);
        }
        return resolved;
    }

    private static String normalize(String path) {
        return path.replace('\\', '/');
    }

    public record Explanation(String target, String fingerprint, String inputTreeSha256,
                              List<Hashing.FileDigest> inputs, Map<String, String> dependencies,
                              List<String> argv, Map<String, String> environment) {
    }
}
