import java.io.BufferedReader;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * B1 dump agent.
 *
 * <p>Phase 1: write every class the JVM defines, after all transformers ran.
 *
 * <p>Phase 2: force-define the full runtime class set. The game lives in an unnamed module,
 * so ModuleLayer is useless, and jar entry names are SRG while runtime names are official.
 * The anchor therefore has to come from the Instrumentation instance: find a class that is
 * already loaded (sanely named {@code net.minecraft.*}), take its loader, and read the module
 * table that loader keeps — those ModuleReferences point at the jars FML actually uses.
 *
 * <p>Usage: {@code -javaagent:dumpagent.jar=<dumpDir>|<jarListFile>}
 */
public final class DumpAgent {
    private static Path root;
    private static Instrumentation instrumentation;

    public static void premain(String args, Instrumentation inst) throws Exception {
        instrumentation = inst;
        String spec = args == null ? "" : args;
        String[] parts = spec.split("\\|", 3);
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
                }
                return null;
            }
        }, false);

        startForcedLoader(parts.length >= 2 ? parts[1] : "", parts.length >= 3 ? Long.parseLong(parts[2].trim()) : 40_000L);
    }

    private static void startForcedLoader(String jarList, long waitMs) {
        Thread worker = new Thread(() -> {
            /* The client needs longer than the server to load its first net.minecraft class, so
               the agent polls instead of sleeping a fixed amount. */
            long deadline = System.currentTimeMillis() + Math.max(waitMs, 30_000L);
            Class<?> anchor = null;
            while (System.currentTimeMillis() < deadline) {
                anchor = findLoadedAnchor();
                if (anchor != null) {
                    break;
                }
                try {
                    Thread.sleep(2_000L);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
            if (anchor == null) {
                System.err.println("[dumpagent] no loaded net.minecraft.* class to anchor on after "
                        + waitMs + " ms");
                return;
            }
            ClassLoader loader = anchor.getClassLoader();
            System.err.println("[dumpagent] anchor=" + anchor.getName()
                    + " loader=" + (loader == null ? "bootstrap" : loader.getClass().getName())
                    + " module=" + anchor.getModule().getName()
                    + " layer=" + (anchor.getModule().getLayer() == null ? "none" : "present"));

            Set<Path> roots = new LinkedHashSet<>();
            collectLoaderRoots(loader, roots);
            collectModuleClassLoaderRoots(loader, roots);
            if (anchor.getModule().getLayer() != null) {
                collectLayerRoots(anchor, roots);
            }
            System.err.println("[dumpagent] runtime roots: " + roots.size());

            int ok = 0;
            int fail = 0;
            for (Path candidate : roots) {
                List<String> names = namesFrom(candidate);
                if (names.isEmpty()) {
                    continue;
                }
                int rootOk = 0;
                int rootFail = 0;
                for (String name : names) {
                    if (define(name, loader) || define(name, ClassLoader.getSystemClassLoader())) {
                        rootOk++;
                    } else {
                        rootFail++;
                    }
                }
                ok += rootOk;
                fail += rootFail;
                System.err.println("[dumpagent] root " + candidate + " ok=" + rootOk + " fail=" + rootFail);
            }

            int jarOk = 0;
            int jarFail = 0;
            if (!jarList.isEmpty()) {
                for (String name : readClassNames(Paths.get(jarList))) {
                    if (define(name, loader) || define(name, ClassLoader.getSystemClassLoader())) {
                        jarOk++;
                    } else {
                        jarFail++;
                    }
                }
            }
            System.err.println("[dumpagent] forced roots ok=" + ok + " fail=" + fail
                    + "; jars ok=" + jarOk + " fail=" + jarFail);
        }, "dumpagent-forced-loader");
        worker.setDaemon(true);
        worker.start();
    }

    /** Finds a loaded game class through the instrumentation, not through a thread context loader. */
    private static Class<?> findLoadedAnchor() {
        Class<?> fallback = null;
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            String name = loaded.getName();
            if (!name.startsWith("net.minecraft.")) {
                continue;
            }
            if (loaded.getClassLoader() == null) {
                continue;
            }
            if (name.equals("net.minecraft.SharedConstants")) {
                return loaded;
            }
            if (fallback == null && name.contains(".")) {
                fallback = loaded;
            }
        }
        return fallback;
    }

    private static void collectLoaderRoots(ClassLoader loader, Set<Path> roots) {
        ClassLoader current = loader;
        while (current != null) {
            URL[] urls = null;
            if (current instanceof URLClassLoader urlLoader) {
                urls = urlLoader.getURLs();
            } else {
                try {
                    Method method = current.getClass().getMethod("getURLs");
                    Object value = method.invoke(current);
                    if (value instanceof URL[] array) {
                        urls = array;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (urls != null) {
                for (URL url : urls) {
                    addRoot(url, roots);
                }
            }
            current = current.getParent();
        }
    }

    /** Reads whatever map of ModuleReference / Module the loader keeps. */
    private static void collectModuleClassLoaderRoots(ClassLoader loader, Set<Path> roots) {
        if (loader == null) {
            return;
        }
        Class<?> type = loader.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(loader);
                    if (value instanceof Map<?, ?> map) {
                        for (Object entry : map.values()) {
                            if (entry instanceof ModuleReference reference) {
                                reference.location().ifPresent(uri -> addLocation(uri, roots));
                            } else if (entry instanceof java.lang.Module module) {
                                collectLayerRootsForModule(module, roots);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            type = type.getSuperclass();
        }
    }

    private static void collectLayerRoots(Class<?> anchor, Set<Path> roots) {
        var layer = anchor.getModule().getLayer();
        if (layer == null) {
            return;
        }
        for (var module : layer.modules()) {
            Optional<ResolvedModule> resolved = layer.configuration().findModule(module.getName());
            resolved.ifPresent(value -> value.reference().location()
                    .ifPresent(uri -> addLocation(uri, roots)));
        }
    }

    private static void collectLayerRootsForModule(java.lang.Module module, Set<Path> roots) {
        var layer = module.getLayer();
        if (layer == null) {
            return;
        }
        var configuration = layer.configuration();
        configuration.findModule(module.getName()).ifPresent(value ->
                value.reference().location().ifPresent(uri -> addLocation(uri, roots)));
    }

    /** Handles file: as well as FML's union:&lt;jar&gt;%23&lt;n&gt;!/ locations. */
    private static void addLocation(URI uri, Set<Path> roots) {
        String text = uri.toString();
        if (text.startsWith("union:")) {
            text = text.substring("union:".length());
        }
        int bang = text.indexOf('!');
        if (bang >= 0) {
            text = text.substring(0, bang);
        }
        for (String marker : new String[]{"%23", "#"}) {
            int index = text.indexOf(marker);
            if (index >= 0) {
                text = text.substring(0, index);
            }
        }
        try {
            Path path = Paths.get(URLDecoder.decode(text, StandardCharsets.UTF_8));
            if (Files.exists(path)) {
                roots.add(path);
            }
        } catch (Exception ignored) {
        }
    }

    private static void addRoot(URL url, Set<Path> roots) {
        if (url == null || !"file".equals(url.getProtocol())) {
            return;
        }
        try {
            Path path = Paths.get(url.toURI());
            if (Files.exists(path)) {
                roots.add(path);
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean define(String name, ClassLoader loader) {
        try {
            Class.forName(name, false, loader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static List<String> namesFrom(Path path) {
        List<String> names = new ArrayList<>();
        try {
            if (Files.isDirectory(path)) {
                try (var stream = Files.walk(path)) {
                    stream.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                        String rel = path.relativize(p).toString().replace('\\', '/');
                        if (!rel.startsWith("META-INF/") && !rel.startsWith("module-info")) {
                            names.add(rel.substring(0, rel.length() - 6).replace('/', '.'));
                        }
                    });
                }
            } else if (path.toString().endsWith(".jar")) {
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
