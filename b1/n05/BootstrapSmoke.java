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
            seedConfigFiles(gameDir);
            seedFmlLoader(gameDir);
        } catch (Throwable error) {
            System.out.println("cannot seed FMLPaths: " + error);
        }
    }

    /**
     * FMLLoader keeps the loading mod list in a private static; mods go through
     * {@code FMLLoader.getLoadingModList()} instead of {@code LoadingModList.get()}, so the
     * shim has to be installed where they look.
     */
    private static void seedFmlLoader(Path gameDir) {
        try {
            Class<?> loader = Class.forName("net.neoforged.fml.loading.FMLLoader");
            Class<?> loadingList = Class.forName("net.neoforged.fml.loading.LoadingModList");
            java.lang.reflect.Field field = loader.getDeclaredField("loadingModList");
            field.setAccessible(true);
            Object list = loadingList.getMethod("get").invoke(null);
            field.set(null, list);
            java.lang.reflect.Field dist = loader.getDeclaredField("dist");
            dist.setAccessible(true);
            dist.set(null, Class.forName("net.neoforged.api.distmarker.Dist").getEnumConstants()[0]);
            java.lang.reflect.Field game = loader.getDeclaredField("gamePath");
            game.setAccessible(true);
            game.set(null, gameDir);
            System.out.println("seeded FMLLoader");
        } catch (Throwable error) {
            System.out.println("cannot seed FMLLoader: " + error);
        }
    }

    /** Configs baked into the image; mods parse them from their own {@code <clinit>}. */
    private static void seedConfigFiles(Path gameDir) {
        int copied = 0;
        try (java.io.InputStream list = BootstrapSmoke.class.getResourceAsStream("/b1-config-list.txt")) {
            if (list == null) {
                System.out.println("no baked configs");
                return;
            }
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(list, "UTF-8"));
            String name;
            while ((name = reader.readLine()) != null) {
                name = name.trim();
                if (name.isEmpty()) {
                    continue;
                }
                try (java.io.InputStream stream = BootstrapSmoke.class.getResourceAsStream("/b1-config/" + name)) {
                    if (stream == null) {
                        continue;
                    }
                    Path target = gameDir.resolve("config").resolve(name);
                    Files.createDirectories(target.getParent());
                    Files.copy(stream, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    copied++;
                }
            }
        } catch (Throwable error) {
            System.out.println("cannot seed configs: " + error);
        }
        System.out.println("seeded configs: " + copied);
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
