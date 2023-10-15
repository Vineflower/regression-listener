package org.vineflower.jardecomp;

import org.jetbrains.java.decompiler.main.Fernflower;
import org.jetbrains.java.decompiler.main.decompiler.DirectoryResultSaver;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class Main {
    public static void main(String[] args) throws Exception {
        Path manifest = Path.of("dlmanifest.txt");

        List<String> manifestLines = Files.readAllLines(manifest);

        // Run decompiler on jars

        Map<String, Object> props = new HashMap<>(IFernflowerPreferences.DEFAULTS);
        props.put(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES, "1");
        props.put(IFernflowerPreferences.REMOVE_SYNTHETIC, "1");
        props.put(IFernflowerPreferences.INCLUDE_ENTIRE_CLASSPATH, "1");
        props.put(IFernflowerPreferences.PATTERN_MATCHING, "1");
        props.put(IFernflowerPreferences.TERNARY_CONDITIONS, "1");
        props.put(IFernflowerPreferences.THREADS, String.valueOf(1));

        Path input = Paths.get("input");
        Path output = Paths.get("scratch", "output");

        int i = 0;
        for (String line : manifestLines) {
            long start = System.currentTimeMillis();
            String fileName = line.substring(line.lastIndexOf('/') + 1);
            String name = fileName.replace(".jar", "");

            delete(output.resolve(name));
            Files.createDirectories(output.resolve(name));

            System.out.println("Decompiling " + fileName + " (" + ((++i) / (double) manifestLines.size()) * 100 + "%)");

            Fernflower vineflower = new Fernflower(
                    new DirectoryResultSaver(output.resolve(name).toFile()),
                    props,
                    new IFernflowerLogger() {
                        @Override
                        public void writeMessage(String s, Severity severity) {
                            if (severity.ordinal() >= Severity.WARN.ordinal()) {
                                System.out.println(s);
                            }
                        }

                        @Override
                        public void writeMessage(String s, Severity severity, Throwable throwable) {
                            System.err.println(s);
                            throwable.printStackTrace(System.err);
                        }
                    }
            );

            vineflower.addSource(input.resolve(fileName).toFile());
            vineflower.decompileContext();

            System.out.println("Done! Took " + (System.currentTimeMillis() - start) / 1000.0 + " seconds");
        }
    }

    private static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        Stream<Path> walk = Files.walk(dir);
        try (walk) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
