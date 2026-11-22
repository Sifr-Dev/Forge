#!/usr/bin/env bash
# Forge adapter verification: builds a real minimal project per supported
# toolchain, runs `sifr-forge run` twice (cold, warm) and verifies the second
# run reports CACHE_HIT. Each case prints one JSON line.
#
# Usage: bench/adapter-matrix.sh <output-file>
# Missing toolchains are reported as "skipped" with the reason, never as passed.
set -uo pipefail
cd "$(dirname "$0")/.."   # forge repo root

JAR="$PWD/target/sifr-forge.jar"
OUT="${1:?output file required}"
WORK="$(mktemp -d /tmp/forge-adapters-XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

case_result() {  # name status detail
  printf '{"adapter":"%s","status":"%s","detail":"%s"}\n' "$1" "$2" "$3" | tee -a "$OUT"
}

verify() {  # name workdir manifest-targets...
  local name="$1"; shift
  local dir="$1"; shift
  local rc=0
  ( cd "$dir" && java -jar "$JAR" run forge.json "$@" > run1.log 2>&1 ) || rc=1
  if [ $rc -ne 0 ]; then
    case_result "$name" FAILED "cold run failed: $(tail -c 200 "$dir/run1.log" | tr '\n' ' ')"
    return
  fi
  ( cd "$dir" && java -jar "$JAR" run forge.json "$@" > run2.log 2>&1 ) || rc=1
  if [ $rc -ne 0 ]; then
    case_result "$name" FAILED "warm run failed: $(tail -c 200 "$dir/run2.log" | tr '\n' ' ')"
    return
  fi
  if ! grep -q CACHE_HIT "$dir/run2.log"; then
    case_result "$name" FAILED "warm run did not restore from cache: $(cat "$dir/run2.log" | tr '\n' ' ')"
    return
  fi
  case_result "$name" PASSED "cold run + verified warm CACHE_HIT"
}

# --- exec (no external toolchain) ---
D="$WORK/exec"; mkdir -p "$D/src"
cat > "$D/forge.json" <<'EOF'
{"version":1,"targets":[{"id":"hello","adapter":"exec","argv":["/bin/sh","-c","mkdir -p out && printf 'forge-exec' > out/hello.txt"],"cwd":".","inputs":["src/in.txt"],"outputs":["out/hello.txt"]}]}
EOF
printf 'exec input' > "$D/src/in.txt"
verify exec "$D"

# --- cmake + ninja ---
if command -v cmake >/dev/null && command -v ninja >/dev/null; then
  D="$WORK/cmake"; mkdir -p "$D/src"
  cat > "$D/src/main.c" <<'EOF'
#include <stdio.h>
int main(void) { FILE *f = fopen("out/token.txt", "w"); if (!f) return 1; fputs("cmake-ok", f); fclose(f); return 0; }
EOF
  cat > "$D/CMakeLists.txt" <<'EOF'
cmake_minimum_required(VERSION 3.20)
project(forge_check C)
set(CMAKE_GENERATOR Ninja)
add_executable(token src/main.c)
target_compile_definitions(token PRIVATE OUT_DIR="${CMAKE_SOURCE_DIR}/out")
EOF
  cat > "$D/forge.json" <<'EOF'
{"version":1,"targets":[
 {"id":"configure","adapter":"cmake","argv":["-S",".","-B","build","-G","Ninja"],"outputs":["build/build.ninja"]},
 {"id":"build","adapter":"ninja","argv":["-C","build"],"dependsOn":["configure"],"outputs":["build/token"]},
 {"id":"run","adapter":"exec","argv":["/bin/sh","-c","mkdir -p out && ./build/token"],"dependsOn":["build"],"outputs":["out/token.txt"]}]}
EOF
  verify cmake-ninja "$D"
else
  case_result cmake-ninja SKIPPED "cmake or ninja not installed"
fi

# --- cargo ---
if command -v cargo >/dev/null; then
  D="$WORK/cargo"; mkdir -p "$D/src"
  cat > "$D/Cargo.toml" <<'EOF'
[package]
name = "forge_check"
version = "0.1.0"
edition = "2021"
[[bin]]
name = "forge_check"
path = "src/main.rs"
[profile.dev]
incremental = false
EOF
  cat > "$D/src/main.rs" <<'EOF'
fn main() { std::fs::write("out/token.txt", "cargo-ok").unwrap(); }
EOF
  cat > "$D/forge.json" <<'EOF'
{"version":1,"targets":[
 {"id":"build","adapter":"cargo","argv":["--offline"],"inputs":["src/main.rs","Cargo.toml"],"outputs":["target/debug/forge_check"]},
 {"id":"run","adapter":"exec","argv":["/bin/sh","-c","mkdir -p out && ./target/debug/forge_check"],"dependsOn":["build"],"outputs":["out/token.txt"]}]}
EOF
  CARGO_HOME="$WORK/cargo-home" verify cargo "$D"
else
  case_result cargo SKIPPED "cargo not installed"
fi

# --- zig ---
if command -v zig >/dev/null; then
  D="$WORK/zig"; mkdir -p "$D/out"
  # A build-only case keeps the check toolchain-version agnostic: Zig 0.17
  # moved several std.fs helpers, so an artifact-writing program would test
  # this machine's Zig rather than Forge's adapter.
  cat > "$D/main.zig" <<'EOF'
pub fn main() void {}
EOF
  cat > "$D/forge.json" <<'EOF'
{"version":1,"targets":[{"id":"build","adapter":"zig","argv":["build-exe","main.zig","-femit-bin=out/forge_check","-O","Debug"],"inputs":["main.zig"],"outputs":["out/forge_check"]}]}
EOF
  verify zig "$D"
else
  case_result zig SKIPPED "zig not installed"
fi

# --- go ---
if command -v go >/dev/null; then
  D="$WORK/go"; mkdir -p "$D/out"
  cat > "$D/go.mod" <<'EOF'
module forgecheck

go 1.24
EOF
  cat > "$D/main.go" <<'EOF'
package main

import "os"

func main() { os.WriteFile("out/token.txt", []byte("go-ok"), 0o644) }
EOF
  cat > "$D/forge.json" <<'EOF'
{"version":1,"targets":[
 {"id":"build","adapter":"go","argv":["-o","out/forge_check","."],"inputs":["main.go","go.mod"],"outputs":["out/forge_check"]}]}
EOF
  verify go "$D"
else
  case_result go SKIPPED "go not installed"
fi

# --- maven ---
if command -v mvn >/dev/null; then
  D="$WORK/maven"; mkdir -p "$D/src/main/java/check"
  cat > "$D/pom.xml" <<'EOF'
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>check</groupId><artifactId>forge-check</artifactId><version>0.1.0</version>
  <properties><maven.compiler.release>25</maven.compiler.release></properties>
  <build><plugins>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-jar-plugin</artifactId><version>3.4.2</version>
      <configuration><archive><manifest><mainClass>check.Main</mainClass></manifest></archive></configuration></plugin>
  </plugins></build>
</project>
EOF
  cat > "$D/src/main/java/check/Main.java" <<'EOF'
package check;
import java.nio.file.*;
public class Main {
    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of("out"));
        Files.writeString(Path.of("out/token.txt"), "maven-ok");
    }
}
EOF
  cat > "$D/forge.json" <<'EOF'
{"version":1,"targets":[
 {"id":"build","adapter":"maven","argv":["-q","--batch-mode","--no-transfer-progress","package"],"inputs":["pom.xml","src/main/java/check/Main.java"],"outputs":["target/forge-check-0.1.0.jar"]},
 {"id":"run","adapter":"exec","argv":["/usr/bin/java","-jar","target/forge-check-0.1.0.jar"],"dependsOn":["build"],"outputs":["out/token.txt"]}]}
EOF
  verify maven "$D"
else
  case_result maven SKIPPED "mvn not installed"
fi

# --- uv (python packaging entry point) ---
if command -v uv >/dev/null; then
  D="$WORK/uv"; mkdir -p "$D/src"
  cat > "$D/pyproject.toml" <<'EOF'
[project]
name = "forge-check"
version = "0.1.0"
requires-python = ">=3.12"
EOF
  cat > "$D/step.py" <<'EOF'
import pathlib
pathlib.Path("out").mkdir(exist_ok=True)
pathlib.Path("out/token.txt").write_text("uv-ok")
EOF
  cat > "$D/forge.json" <<'EOF'
{"version":1,"targets":[{"id":"run","adapter":"uv","argv":["run","--no-project","python3","step.py"],"inputs":["step.py","pyproject.toml"],"outputs":["out/token.txt"]}]}
EOF
  verify uv "$D"
else
  case_result uv SKIPPED "uv not installed"
fi

# --- node ---
if command -v node >/dev/null; then
  D="$WORK/node"; mkdir -p "$D/out"
  cat > "$D/step.mjs" <<'EOF'
import { mkdirSync, writeFileSync } from "node:fs";
mkdirSync("out", { recursive: true });
writeFileSync("out/token.txt", "node-ok");
EOF
  cat > "$D/forge.json" <<'EOF'
{"version":1,"targets":[{"id":"run","adapter":"node","argv":["step.mjs"],"inputs":["step.mjs"],"outputs":["out/token.txt"]}]}
EOF
  verify node "$D"
else
  case_result node SKIPPED "node not installed"
fi

# --- dart ---
if command -v dart >/dev/null; then
  D="$WORK/dart"; mkdir -p "$D/out"
  cat > "$D/token.dart" <<'EOF'
import 'dart:io';
void main() {
  Directory('out').createSync(recursive: true);
  File('out/token.txt').writeAsStringSync('dart-ok');
}
EOF
  cat > "$D/forge.json" <<'EOF'
{"version":1,"targets":[{"id":"build","adapter":"dart","argv":["compile","exe","token.dart","-o","out/token"],"inputs":["token.dart"],"outputs":["out/token"]}]}
EOF
  verify dart "$D"
else
  case_result dart SKIPPED "dart not installed"
fi

# --- dotnet ---
if command -v dotnet >/dev/null; then
  D="$WORK/dotnet"; mkdir -p "$D"
  cat > "$D/forge.csproj" <<'EOF'
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFramework>net10.0</TargetFramework>
    <AssemblyName>token</AssemblyName>
  </PropertyGroup>
</Project>
EOF
  cat > "$D/Program.cs" <<'EOF'
using System.IO;
System.IO.Directory.CreateDirectory("out");
System.IO.File.WriteAllText("out/token.txt", "dotnet-ok");
EOF
  cat > "$D/forge.json" <<'EOF'
{"version":1,"targets":[{"id":"build","adapter":"dotnet","argv":["build","forge.csproj","-c","Release","-o","out/bin"],"inputs":["Program.cs","forge.csproj"],"outputs":["out/bin/token.dll"]}]}
EOF
  verify dotnet "$D"
else
  case_result dotnet SKIPPED "dotnet not installed"
fi

echo "adapter matrix complete"
