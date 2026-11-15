import java.io.BufferedReader;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * B1 dump agent.
 *
 * <p>Phase 1: write every class the JVM defines, after all transformers ran.
 *
 * <p>Phase 2: NeoForge only maps the classes it loads, and the on-disk jar entry names are not
 * the runtime names (SRG vs official), so neither "loaded only" nor "enumerate jars" produces a
 * complete graph. Instead, walk the <em>module layer</em> the game runs in: every module's
 * reference points at the jar FML actually uses, and its own class loader knows how to define
 * the names inside it.
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

        startForcedLoader(parts.length == 2 ? parts[1] : "");
    }

    private static void startForcedLoader(String jarList) {
        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(40_000L);
            } catch (InterruptedException interrupted) {
                return;
            }

            Class<?> anchor = null;
            try {
                anchor = Class.forName("net.minecraft.SharedConstants", false,
                        Thread.currentThread().getContextClassLoader());
            } catch (Throwable ignored) {
            }

            int moduleOk = 0;
            int moduleFail = 0;
            ModuleLayer layer = anchor == null ? null : anchor.getModule().getLayer();
            if (layer == null) {
                System.err.println("[dumpagent] no module layer on the anchor class");
            } else {
                System.err.println("[dumpagent] module layer with " + layer.modules().size() + " modules");
                for (Module module : layer.modules()) {
                    Optional<ResolvedModule> resolved =
                            layer.configuration().findModule(module.getName());
                    if (resolved.isEmpty()) {
                        continue;
                    }
                    ModuleReference reference = resolved.get().reference();
                    Optional<URI> location = reference.location();
                    if (location.isEmpty()) {
                        continue;
                    }
                    List<String> names = namesFrom(location.get());
                    if (names.isEmpty()) {
                        continue;
                    }
                    int ok = 0;
                    int fail = 0;
                    ClassLoader loader = module.getClassLoader();
                    for (String name : names) {
                        if (define(name, loader)) {
                            ok++;
                        } else {
                            fail++;
                        }
                    }
                    moduleOk += ok;
                    moduleFail += fail;
                    System.err.println("[dumpagent] module " + module.getName() + " " + location.get()
                            + " ok=" + ok + " fail=" + fail);
                }
            }

            int jarOk = 0;
            int jarFail = 0;
            if (!jarList.isEmpty()) {
                for (String name : readClassNames(Paths.get(jarList))) {
                    if (define(name, ClassLoader.getSystemClassLoader())) {
                        jarOk++;
                    } else {
                        jarFail++;
                    }
                }
            }

            System.err.println("[dumpagent] forced modules ok=" + moduleOk + " fail=" + moduleFail
                    + "; jars ok=" + jarOk + " fail=" + jarFail);
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

    private static List<String> namesFrom(URI location) {
        List<String> names = new ArrayList<>();
        if (!"file".equals(location.getScheme())) {
            return names;
        }
        try {
            Path path = Paths.get(location);
            if (!Files.isRegularFile(path)) {
                return names;
            }
            try (JarFile jar = new JarFile(path.toFile())) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String entry = entries.nextElement().getName();
                    if (entry.endsWith(".class") && !entry.startsWith("META-INF/")
                            && !entry.startsWith("module-info")) {
                        names.add(entry.substring(0, entry.length() - 6).replace('/', '.'));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return names;
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
                        if (entry.endsWith(".class") && !entry.startsWith("META-INF/")
                                && !entry.startsWith("module-info")) {
                            names.add(entry.substring(0, entry.length() - 6).replace('/', '.'));
                        }
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
