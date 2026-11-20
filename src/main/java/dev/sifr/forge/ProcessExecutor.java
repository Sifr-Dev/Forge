package dev.sifr.forge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Bounded direct process execution; this is not a security sandbox. */
public final class ProcessExecutor {
    public record Result(int exitCode, boolean timedOut, String stdout, String stderr, Duration elapsed) {}
    private static final int MAX_CAPTURE = 1024 * 1024;

    public Result run(Manifest manifest, Target target) throws ForgeException {
        Command command = Adapters.build(target);
        ProcessBuilder builder = new ProcessBuilder(command.argv());
        Path cwd = manifest.projectRoot().resolve(target.cwd()).normalize();
        if (!cwd.startsWith(manifest.projectRoot()) || !Files.isDirectory(cwd)) throw new ForgeException("invalid working directory " + cwd);
        builder.directory(cwd.toFile());
        builder.environment().clear();
        for (String name : target.envAllowlist()) {
            String value = System.getenv(name);
            if (value != null) builder.environment().put(name, value);
        }
        builder.environment().putAll(target.env());
        long start = System.nanoTime();
        try {
            Process process = builder.start();
            StringBuilder stdout = new StringBuilder(), stderr = new StringBuilder();
            try (ExecutorService drain = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> out = drain.submit(() -> drain(process.getInputStream(), stdout));
                Future<?> err = drain.submit(() -> drain(process.getErrorStream(), stderr));
                boolean exited = process.waitFor(target.timeout().toMillis(), TimeUnit.MILLISECONDS);
                if (!exited) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
                out.get(5, TimeUnit.SECONDS); err.get(5, TimeUnit.SECONDS);
                return new Result(exited ? process.exitValue() : -1, !exited, stdout.toString(), stderr.toString(), Duration.ofNanos(System.nanoTime() - start));
            }
        } catch (IOException e) {
            throw new ForgeException("cannot execute " + String.join(" ", command.argv()) + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ForgeException("interrupted while executing " + target.id(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new ForgeException("cannot drain process output for " + target.id(), e);
        }
    }

    private static void drain(InputStream input, StringBuilder output) {
        try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                if (output.length() < MAX_CAPTURE) output.append(buffer, 0, Math.min(count, MAX_CAPTURE - output.length()));
            }
        } catch (IOException e) {
            if (output.isEmpty()) output.append("[output drain failed: ").append(e.getMessage()).append(']');
        }
    }
}
