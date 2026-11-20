package dev.sifr.forge;

import java.util.List;
import java.util.Map;

/** Deterministic execution order and reverse dependency index. */
public record BuildPlan(Manifest manifest, List<Target> order, Map<String, List<String>> dependents) {
    public BuildPlan {
        order = List.copyOf(order);
        dependents = Map.copyOf(dependents);
    }

    public static BuildPlan create(Manifest manifest) throws ForgeException {
        return new BuildPlan(manifest, Dag.topologicalOrder(manifest), Dag.dependents(manifest));
    }

    public List<Target> selection(List<String> requested) throws ForgeException {
        return Dag.select(this, requested);
    }
}
