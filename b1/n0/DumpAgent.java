import java.io.BufferedReader;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * B1 dump agent.
 *
 * <p>Phase 1: write every class the JVM defines, after all transformers ran.
 *
 * <p>Phase 2: force-define the full runtime class set so phase 1 can capture it. The game runs
 * in an <em>unnamed</em> module, so {@code ModuleLayer} is useless here; instead we ask the
 * loader that owns a real game class for its roots (URLClassLoader or a reflective
 * {@code getURLs()}), because those roots are the jars FML actually reads after remapping.
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

            ClassLoader loader = anchor == null ? null : anchor.getClassLoader();
            System.err.println("[dumpagent] anchor loader = "
                    + (loader == null ? "bootstrap" : loader.getClass().getName())
                    + "; module = " + (anchor == null ? "?" : anchor.getModule().getName()));

            Set<Path> roots = new LinkedHashSet<>();
            collectRoots(loader, roots);
            collectModuleRoots(anchor, roots);
            System.err.println("[dumpagent] runtime roots: " + roots.size());

            int ok = 0;
            int fail = 0;
            for (Path candidate : roots) {
                List<String> names = namesFrom(candidate, loader);
                if (names.isEmpty()) {
                    continue;
                }
                int rootOk = 0;
                int rootFail = 0;
                for (String name : names) {
                    if (define(name, loader)) {
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

    /** Collects the class path roots of a loader chain, including a reflective getURLs(). */
    private static void collectRoots(ClassLoader loader, Set<Path> roots) {
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
            try {
                String field = null;
                for (String candidate : new String[]{"ucp", "path", "urls"}) {
                    try {
                        current.getClass().getDeclaredField(candidate);
                        field = candidate;
                        break;
                    } catch (NoSuchFieldException ignored) {
                    }
                }
                if (field != null) {
                    var declared = current.getClass().getDeclaredField(field);
                    declared.setAccessible(true);
                    Object value = declared.get(current);
                    if (value != null && value.getClass().getName().contains("URLClassPath")) {
                        Method getUrls = value.getClass().getMethod("getURLs");
                        for (Object url : (Object[]) getUrls.invoke(value)) {
                            addRoot((URL) url, roots);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            current = current.getParent();
        }
    }

    private static void collectModuleRoots(Class<?> anchor, Set<Path> roots) {
        if (anchor == null || anchor.getModule().getLayer() == null) {
            return;
        }
        var layer = anchor.getModule().getLayer();
        for (var module : layer.modules()) {
            Optional<ResolvedModule> resolved = layer.configuration().findModule(module.getName());
            if (resolved.isEmpty()) {
                continue;
            }
            ModuleReference reference = resolved.get().reference();
            reference.location().ifPresent(uri -> addRoot(uriToUrl(uri), roots));
        }
    }

    private static URL uriToUrl(URI uri) {
        try {
            return uri.getScheme().equals("file") ? uri.toURL() : null;
        } catch (Exception ignored) {
            return null;
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

    private static List<String> namesFrom(Path path, ClassLoader loader) {
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
