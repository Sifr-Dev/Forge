#!/usr/bin/env bash
# Forge scheduling and caching benchmark on a synthetic polyglot-shaped graph.
#
# Usage: bench/forge-benchmark.sh <output-dir> [width] [levels]
# Emits JSON lines: plan evaluation time, cold/warm wall clock and cache-hit
# counts for 1/2/4/8 workers, plus a long-tail failure-recovery measurement.
set -uo pipefail
cd "$(dirname "$0")/.."

JAR="$PWD/target/sifr-forge.jar"
OUT="${1:?output directory required}"
WIDTH="${2:-12}"
LEVELS="${3:-4}"
mkdir -p "$OUT"
WORK="$(mktemp -d /tmp/forge-bench-XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

python3 - "$WORK" "$WIDTH" "$LEVELS" <<'PY'
import json, pathlib, sys
work, width, levels = pathlib.Path(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3])
targets = []
for level in range(levels):
    for index in range(width):
        tid = f"L{level}-T{index}"
        out = f"out/{tid}.txt"
        deps = [] if level == 0 else [f"L{level - 1}-T{index}", f"L{level - 1}-T{(index + 1) % width}"]
        targets.append({
            "id": tid, "adapter": "exec",
            "argv": ["/bin/sh", "-c", f"sleep 0.02; mkdir -p out; printf '{tid}' > {out}"],
            "dependsOn": deps, "outputs": [out],
        })
(work / "forge.json").write_text(json.dumps({"version": 1, "targets": targets}, indent=2))
print(f"graph: {len(targets)} targets, {width} wide x {levels} levels")
PY

TARGETS=$(find "$WORK/out" -maxdepth 0 2>/dev/null; find "$WORK" -name forge.json | wc -l)
echo "graph: $(python3 -c "import json,sys;print(len(json.load(open('$WORK/forge.json'))['targets']))") targets"

emit() { printf '%s\n' "$1" | tee -a "$OUT/results.jsonl"; }

# --- plan evaluation (graph construction + fingerprint-free selection) ---
total_ms=0
for _ in 1 2 3 4 5; do
  start=$(date +%s%N)
  java -jar "$JAR" plan "$WORK/forge.json" > /dev/null
  total_ms=$(( total_ms + ($(date +%s%N) - start) / 1000000 ))
done
emit "{\"measurement\":\"plan_cli_total\",\"runs\":5,\"mean_ms\":$(( total_ms / 5 )),\"note\":\"includes JVM startup\"}"

for jobs in 1 2 4 8; do
  # cold: fresh project copy (no .forge cache)
  cold_dir="$WORK/cold-$jobs"
  rm -rf "$cold_dir"; mkdir -p "$cold_dir"
  cp "$WORK/forge.json" "$cold_dir/"
  start=$(date +%s%N)
  ( cd "$cold_dir" && java -jar "$JAR" run forge.json --jobs "$jobs" > run-cold.log 2>&1 )
  cold_ms=$(( ($(date +%s%N) - start) / 1000000 ))
  start=$(date +%s%N)
  ( cd "$cold_dir" && java -jar "$JAR" run forge.json --jobs "$jobs" > run-warm.log 2>&1 )
  warm_ms=$(( ($(date +%s%N) - start) / 1000000 ))
  hits=$(grep -c CACHE_HIT "$cold_dir/run-warm.log" || true)
  total=$(grep -cE ' (SUCCEEDED|CACHE_HIT|FAILED|TIMEOUT|SKIPPED)( |$)' "$cold_dir/run-cold.log" || true)
  emit "{\"measurement\":\"run\",\"jobs\":$jobs,\"cold_ms\":$cold_ms,\"warm_ms\":$warm_ms,\"targets\":$total,\"cache_hits\":$hits}"
done

# --- failure recovery: one deep chain fails; dependents must be skipped fast ---
fdir="$WORK/failure"; mkdir -p "$fdir"
python3 - "$fdir" <<'PY'
import json, pathlib, sys
d = pathlib.Path(sys.argv[1])
targets = [{"id": "root", "adapter": "exec", "argv": ["/bin/false"], "outputs": []}]
for i in range(1, 40):
    targets.append({"id": f"n{i}", "adapter": "exec", "argv": ["/bin/sh", "-c", "printf x > n.txt"],
                    "dependsOn": [f"n{i-1}" if i > 1 else "root"], "outputs": ["n.txt"]})
(d / "forge.json").write_text(json.dumps({"version": 1, "targets": targets}, indent=2))
PY
start=$(date +%s%N)
( cd "$fdir" && java -jar "$JAR" run forge.json --jobs 4 > run.log 2>&1 )
rc=$?
fail_ms=$(( ($(date +%s%N) - start) / 1000000 ))
skipped=$(grep -c SKIPPED "$fdir/run.log" || true)
emit "{\"measurement\":\"failure_propagation\",\"exit_code\":$rc,\"wall_ms\":$fail_ms,\"skipped\":$skipped}"
