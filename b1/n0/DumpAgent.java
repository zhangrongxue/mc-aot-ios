import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.ProtectionDomain;

/**
 * Dumps every class the JVM defines, after all transformers (Mixin, AccessTransformer,
 * ModLauncher) have run. Used by the B1 N0 probe: the resulting tree is the input for
 * deciding whether NeoForge's loader bootstrap can be staticised at build time.
 *
 * <p>Usage: {@code -javaagent:dumpagent.jar=<output directory>}
 */
public final class DumpAgent {
    private static Path root;

    public static void premain(String args, Instrumentation inst) throws Exception {
        root = Paths.get(args == null || args.isEmpty() ? "classdump" : args);
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
                    // A dump is diagnostic output: never fail the game because of it.
                }
                return null;
            }
        }, false);
    }
}
