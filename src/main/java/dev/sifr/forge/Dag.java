package dev.sifr.forge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;

/** Cycle-safe DAG algorithms; no hash-map iteration determines a build order. */
public final class Dag {
    private Dag() {
    }

    public static List<Target> topologicalOrder(Manifest manifest) throws ForgeException {
        Map<String, List<String>> dependents = dependents(manifest);
        Map<String, Integer> indegree = new TreeMap<>();
        for (Target target : manifest.orderedTargets()) {
            indegree.put(target.id(), target.dependsOn().size());
        }
        var ready = new PriorityQueue<String>();
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(id);
            }
        });
        var result = new ArrayList<Target>();
        while (!ready.isEmpty()) {
            String id = ready.remove();
            result.add(manifest.targets().get(id));
            for (String dependent : dependents.get(id)) {
                int degree = indegree.compute(dependent, (ignored, value) -> value - 1);
                if (degree == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (result.size() != manifest.targets().size()) {
            var cyclic = new ArrayList<String>();
            indegree.forEach((id, degree) -> {
                if (degree > 0) {
                    cyclic.add(id);
                }
            });
            throw new ForgeException("dependency cycle detected involving: " + String.join(", ", cyclic));
        }
        return List.copyOf(result);
    }

    public static Map<String, List<String>> dependents(Manifest manifest) throws ForgeException {
        var result = new TreeMap<String, List<String>>();
        manifest.targets().keySet().forEach(id -> result.put(id, new ArrayList<>()));
        for (Target target : manifest.orderedTargets()) {
            for (String dependency : target.dependsOn()) {
                if (!manifest.targets().containsKey(dependency)) {
                    throw new ForgeException("target " + target.id() + ": missing dependency '" + dependency + "'");
                }
                result.get(dependency).add(target.id());
            }
        }
        var immutable = new LinkedHashMap<String, List<String>>();
        result.forEach((id, list) -> immutable.put(id, List.copyOf(list)));
        return immutable;
    }

    /** Returns the requested targets and all transitive dependencies in global topological order. */
    public static List<Target> select(BuildPlan plan, List<String> requested) throws ForgeException {
        Set<String> included = new LinkedHashSet<>();
        if (requested.isEmpty()) {
            included.addAll(plan.manifest().targets().keySet());
        } else {
            var pending = new ArrayDeque<String>();
            for (String id : requested) {
                if (!plan.manifest().targets().containsKey(id)) {
                    throw new ForgeException("unknown target '" + id + "'");
                }
                pending.add(id);
            }
            while (!pending.isEmpty()) {
                String id = pending.remove();
                if (included.add(id)) {
                    pending.addAll(plan.manifest().targets().get(id).dependsOn());
                }
            }
        }
        return plan.order().stream().filter(target -> included.contains(target.id())).toList();
    }
}
