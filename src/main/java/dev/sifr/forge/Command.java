package dev.sifr.forge;

import java.util.List;

/** Fully resolved argv. No shell parsing or interpolation occurs. */
public record Command(List<String> argv) {
    public Command {
        argv = List.copyOf(argv);
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("command argv cannot be empty");
        }
    }
}
