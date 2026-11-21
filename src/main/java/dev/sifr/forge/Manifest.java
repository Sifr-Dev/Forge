package dev.sifr.forge;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parsed manifest with a stable, insertion-independent target index. */
public record Manifest(Path projectRoot, Map<String, Target> targets) {
    public Manifest {
        projectRoot = projectRoot.toAbsolutePath().normalize();
        var sorted = new LinkedHashMap<String, Target>();
        targets.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        targets = java.util.Collections.unmodifiableMap(sorted);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("manifest requires at least one target");
        }
        for (var entry : targets.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().id())) {
                throw new IllegalArgumentException("target index does not match target id");
            }
        }
    }

    public List<Target> orderedTargets() {
        return List.copyOf(targets.values());
    }
}
