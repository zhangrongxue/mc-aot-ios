import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * B1 static-runtime smoke: run Minecraft's own data bootstrap without ModLauncher.
 *
 * <p>The image has no FML launch context, so classes that ask {@code FMLPaths.GAMEDIR.get()}
 * (Entity Texture Features was the first) NPE. Seeding the paths is exactly what the real static
 * runtime has to do, so it belongs in the smoke as well.
 */
public final class BootstrapSmoke {
    public static void main(String[] args) throws Exception {
        System.out.println("B1 static-runtime bootstrap smoke");
        seedLoaderPaths();
        seedConfigFormats();

        long started = System.nanoTime();
        net.minecraft.server.Bootstrap.bootStrap();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        System.out.println("bootstrap ms=" + elapsedMs);
        System.out.println("blocks=" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.size());
        System.out.println("items=" + net.minecraft.core.registries.BuiltInRegistries.ITEM.size());
        System.out.println("entities=" + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.size());
        System.out.println("ok");
    }

    private static void seedLoaderPaths() {
        try {
            Path gameDir = Paths.get(System.getProperty("java.io.tmpdir"), "b1-game");
            Files.createDirectories(gameDir);
            Files.createDirectories(gameDir.resolve("config"));
            Files.createDirectories(gameDir.resolve("mods"));
            Class<?> fmlPaths = Class.forName("net.neoforged.fml.loading.FMLPaths");
            Method load = fmlPaths.getMethod("loadAbsolutePaths", Path.class);
            load.invoke(null, gameDir);
            System.out.println("seeded FMLPaths at " + gameDir);
        } catch (Throwable error) {
            System.out.println("cannot seed FMLPaths: " + error);
        }
    }

    /**
     * night-config finds its file formats through ServiceLoader, and a class dump carries no
     * META-INF/services entries, so FerriteCore hits "No suitable format for ...toml".
     * Registering the parsers explicitly replaces what ServiceLoader would have done.
     */
    private static void seedConfigFormats() {
        String[][] formats = {
            {"toml", "com.electronwill.nightconfig.toml.TomlFormat"},
            {"json", "com.electronwill.nightconfig.json.JsonFormat"},
            {"yaml", "com.electronwill.nightconfig.yaml.YamlFormat"},
            {"yml", "com.electronwill.nightconfig.yaml.YamlFormat"},
        };
        try {
            Class<?> detector = Class.forName("com.electronwill.nightconfig.core.file.FormatDetector");
            Method register = detector.getMethod("registerExtension", String.class,
                    Class.forName("com.electronwill.nightconfig.core.ConfigFormat"));
            int registered = 0;
            for (String[] entry : formats) {
                try {
                    Class<?> formatClass = Class.forName(entry[1]);
                    Object format = formatClass.getMethod("instance").invoke(null);
                    register.invoke(null, entry[0], format);
                    registered++;
                } catch (Throwable ignored) {
                    // that parser is not on the classpath
                }
            }
            System.out.println("registered config formats: " + registered);
        } catch (Throwable error) {
            System.out.println("cannot seed config formats: " + error);
        }
    }
}
