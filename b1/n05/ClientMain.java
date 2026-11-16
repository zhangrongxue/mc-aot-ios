import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * B1 client-entry smoke: hand control to Minecraft's own client main inside the image.
 *
 * <p>The registry bootstrap already runs (see BootstrapSmoke); this walks the rest of the real
 * entry point: version detection, FML shims, then {@code net.minecraft.client.main.Main.main}.
 * Run it on a machine with a GPU - the CI macOS VMs have no OpenGL context.
 */
public final class ClientMain {
    public static void main(String[] args) throws Exception {
        System.out.println("B1 client-entry smoke (build 3)");
        Path gameDir = Paths.get(System.getProperty("user.dir"), "run");
        Files.createDirectories(gameDir.resolve("config"));
        Files.createDirectories(gameDir.resolve("mods"));
        String natives = System.getenv("B1_NATIVES");
        if (natives != null && !natives.isEmpty()) {
            Path path = Paths.get(natives).toAbsolutePath();
            System.setProperty("org.lwjgl.librarypath", path.toString());
            System.setProperty("java.library.path", path.toString());
            System.setProperty("org.lwjgl.util.NoChecks", "false");
            System.out.println("lwjgl natives: " + path);
        }
        seedLoaderPaths(gameDir);
        seedFmlLoader(gameDir);
        seedHeadlessGlfw();
        net.minecraft.SharedConstants.tryDetectVersion();
        System.out.println("game version: " + net.minecraft.SharedConstants.getCurrentVersion().getName());

        String[] clientArgs = {
            "--username", "Dev",
            "--version", "1.21.1",
            "--gameDir", gameDir.toString(),
            "--assetsDir", System.getProperty("b1.assets", Paths.get("assets").toAbsolutePath().toString()),
            "--assetIndex", "17",
            "--uuid", "00000000000000000000000000000000",
            "--accessToken", "0",
            "--userType", "legacy",
            "--versionType", "release",
            "--width", "1280",
            "--height", "720",
        };
        System.out.println("starting net.minecraft.client.main.Main with " + clientArgs.length + " args");
        net.minecraft.client.main.Main.main(clientArgs);
    }

    private static void seedLoaderPaths(Path gameDir) {
        try {
            Class<?> fmlPaths = Class.forName("net.neoforged.fml.loading.FMLPaths");
            Method load = fmlPaths.getMethod("loadAbsolutePaths", Path.class);
            load.invoke(null, gameDir);
            System.out.println("seeded FMLPaths at " + gameDir);
        } catch (Throwable error) {
            System.out.println("cannot seed FMLPaths: " + error);
        }
    }

    private static void seedFmlLoader(Path gameDir) {
        try {
            Class<?> loader = Class.forName("net.neoforged.fml.loading.FMLLoader");
            Class<?> loadingList = Class.forName("net.neoforged.fml.loading.LoadingModList");
            Field field = loader.getDeclaredField("loadingModList");
            field.setAccessible(true);
            field.set(null, loadingList.getMethod("get").invoke(null));
            Field dist = loader.getDeclaredField("dist");
            dist.setAccessible(true);
            dist.set(null, Class.forName("net.neoforged.api.distmarker.Dist").getEnumConstants()[0]);
            Field game = loader.getDeclaredField("gamePath");
            game.setAccessible(true);
            game.set(null, gameDir);
            System.out.println("seeded FMLLoader");
            seedBackgroundWaiter();
        } catch (Throwable error) {
            System.out.println("cannot seed FMLLoader: " + error);
        }
    }

    /** BackgroundWaiter submits the bootstrap work to a static pool FML would have created. */
    private static void seedBackgroundWaiter() {
        try {
            Class<?> waiter = Class.forName("net.neoforged.fml.loading.BackgroundWaiter");
            Field runner = waiter.getDeclaredField("runner");
            runner.setAccessible(true);
            System.out.println("BackgroundWaiter.runner before: " + runner.get(null));
            Field tick = Class.forName("net.neoforged.fml.loading.FMLLoader").getDeclaredField("progressWindowTick");
            tick.setAccessible(true);
            if (tick.get(null) == null) {
                tick.set(null, (Runnable) () -> { });
                System.out.println("seeded progressWindowTick");
            }
            if (runner.get(null) == null) {
                runner.set(null, java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "b1-bootstrap");
                    thread.setDaemon(true);
                    return thread;
                }));
                System.out.println("seeded BackgroundWaiter");
            }
        } catch (Throwable error) {
            System.out.println("cannot seed BackgroundWaiter: " + error);
        }
    }

    /**
     * GLFW is asked for an OSMesa-backed context on the null platform, so Minecraft can render
     * into an offscreen buffer on a machine without a GPU. Both hints are set before Minecraft
     * touches GLFW; it never overrides the context creation API.
     */
    private static void seedHeadlessGlfw() {
        String osmesa = System.getenv("B1_OSMESA");
        if (osmesa == null) {
            return;
        }
        System.setProperty("org.lwjgl.opengl.libname", osmesa);
        System.out.println("gl: libname -> " + osmesa);
        try {
            org.lwjgl.glfw.GLFW.glfwInitHint(org.lwjgl.glfw.GLFW.GLFW_PLATFORM, org.lwjgl.glfw.GLFW.GLFW_PLATFORM_NULL);
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_CONTEXT_CREATION_API,
                    org.lwjgl.glfw.GLFW.GLFW_OSMESA_CONTEXT_API);
            System.out.println("glfw: null platform + OSMesa context");
        } catch (Throwable error) {
            System.out.println("cannot configure headless glfw: " + error);
        }
    }
}
