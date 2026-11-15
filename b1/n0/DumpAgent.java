import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * B1 dump agent.
 *
 * <p>Phase 1 (always): write every class the JVM defines, after all transformers ran.
 *
 * <p>Phase 2 (when a jar list is supplied): NeoForge only maps/renames the classes it actually
 * loads, so a dump of a server run is missing everything that was never touched. After the
 * server is up, walk the runtime classpath and force a definition of every class name through
 * the Minecraft/FML loader, so the transformers see all of them and phase 1 captures the
 * official-named result.
 *
 * <p>Usage: {@code -javaagent:dumpagent.jar=<dumpDir>|<jarListFile>}
 */
public final class DumpAgent {
    private static Path root;

    public static void premain(String args, Instrumentation inst) throws Exception {
        String spec = args == null ? "" : args;
        String[] parts = spec.split("\\|", 2);
        root = Paths.get(parts[0].isEmpty() ? "classdump" : parts[0]);
        Files.createDirectories(root);

        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
                                    ProtectionDomain domain, byte[] bytes) {
                if (bytes == null || name == null) {
                    return null;
                }
                try {
                    Path target = root.resolve(name + ".class");
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.write(target, bytes);
                } catch (Exception ignored) {
                    // Diagnostic output must never break the game.
                }
                return null;
            }
        }, false);

        if (parts.length == 2 && !parts[1].isEmpty()) {
            startForcedLoader(Paths.get(parts[1]));
        }
    }

    private static void startForcedLoader(Path jarList) {
        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(45_000L);
            } catch (InterruptedException interrupted) {
                return;
            }
            List<String> names = readClassNames(jarList);
            System.err.println("[dumpagent] forcing " + names.size() + " class names from " + jarList);

            ClassLoader primary = null;
            try {
                primary = Class.forName("net.minecraft.SharedConstants", false,
                        Thread.currentThread().getContextClassLoader()).getClassLoader();
            } catch (Throwable ignored) {
            }

            int ok = 0;
            int fail = 0;
            for (String name : names) {
                if (define(name, primary) || define(name, ClassLoader.getSystemClassLoader())) {
                    ok++;
                } else {
                    fail++;
                }
            }
            System.err.println("[dumpagent] forced ok=" + ok + " fail=" + fail + " total=" + names.size());
        }, "dumpagent-forced-loader");
        worker.setDaemon(true);
        worker.start();
    }

    private static boolean define(String name, ClassLoader loader) {
        try {
            Class.forName(name, false, loader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static List<String> readClassNames(Path jarList) {
        List<String> names = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(jarList, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String jarPath = line.trim();
                if (jarPath.isEmpty()) {
                    continue;
                }
                Path jar = Paths.get(jarPath);
                if (!Files.isRegularFile(jar)) {
                    continue;
                }
                try (JarFile file = new JarFile(jar.toFile())) {
                    Enumeration<JarEntry> entries = file.entries();
                    while (entries.hasMoreElements()) {
                        String entry = entries.nextElement().getName();
                        if (!entry.endsWith(".class") || entry.startsWith("META-INF/")) {
                            continue;
                        }
                        names.add(entry.substring(0, entry.length() - 6).replace('/', '.'));
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception error) {
            System.err.println("[dumpagent] cannot read jar list: " + error);
        }
        return names;
    }
}
