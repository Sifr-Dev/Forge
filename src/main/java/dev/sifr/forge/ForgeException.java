package dev.sifr.forge;

/** An expected, actionable user or build error (no stack trace in normal CLI output). */
public final class ForgeException extends Exception {
    public ForgeException(String message) {
        super(message);
    }

    public ForgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
