package dev.sifr.forge;

import java.util.List;
import java.util.StringJoiner;

/** Display-only argv quoting. Execution never passes this text to a shell. */
public final class ShellDisplay {
    private ShellDisplay() {
    }

    public static String render(List<String> argv) {
        var result = new StringJoiner(" ");
        for (String arg : argv) {
            if (arg.matches("[A-Za-z0-9_./:=+@%,-]+")) {
                result.add(arg);
            } else {
                result.add("'" + arg.replace("'", "'\\''") + "'");
            }
        }
        return result.toString();
    }
}
