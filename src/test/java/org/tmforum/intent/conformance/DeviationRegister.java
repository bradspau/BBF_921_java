package org.tmforum.intent.conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The deviation register {@code conformance/DEVIATIONS.md}. Its machine-readable convention: a line that is exactly
 * {@code case: <name>} (optionally written as a bullet) says the entry applies to that case directory. Entries about
 * policy or extensions may have no such line, or may name existing cases.
 */
public final class DeviationRegister {

    private static final Pattern CASE_LINE = Pattern.compile("^\\s*(?:[-*]\\s+)?case:\\s*([A-Za-z0-9_.-]+)\\s*$");

    private DeviationRegister() {}

    /** @return the case names named by {@code case:} lines in {@code markdown} */
    public static Set<String> caseNames(String markdown) {
        Set<String> names = new TreeSet<>();
        for (String line : markdown.split("\\R")) {
            Matcher m = CASE_LINE.matcher(line);
            if (m.matches()) names.add(m.group(1));
        }
        return names;
    }

    /** @return the case names named in the register file in {@code conformanceRoot}; empty when there is no file */
    public static Set<String> caseNames(Path conformanceRoot) {
        Path file = conformanceRoot.resolve("DEVIATIONS.md");
        if (!Files.isRegularFile(file)) return Set.of();
        try {
            return caseNames(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
