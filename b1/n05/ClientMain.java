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
        // GLFW's WGL path and the drivers it loads use deep native stacks; the substrate main
        // thread's default was enough for the bootstrap but not for the GL initialisation.
        Thread worker = new Thread(null, () -> {
            try {
                run(args);
            } catch (Throwable error) {
                System.out.println("CLIENT FAILED: " + error);
                error.printStackTrace(System.out);
            }
        }, "b1-main", 256L * 1024L * 1024L);
        worker.start();
        worker.join();
    }

    private static void run(String[] args) throws Exception {
        System.out.println("B1 client-entry smoke (build 4)");
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
        String stage = System.getenv("B1_STAGE");
        if (stage != null && stage.equals("bootstrap")) {
            // Bisection aid: run the registry bootstrap only, so a crash here means the static
            // runtime itself is at fault rather than the client's window/renderer path.
            long started = System.nanoTime();
            net.minecraft.server.Bootstrap.bootStrap();
            System.out.println("bootstrap ok in " + ((System.nanoTime() - started) / 1_000_000L) + " ms");
            System.out.println("blocks=" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.size());
            return;
        }
        if (stage != null && stage.equals("glfw")) {
            // Second bisection step: does LWJGL's GLFW work inside the image at all?
            System.out.println("glfw stage: init=" + org.lwjgl.glfw.GLFW.glfwInit());
            System.out.println("glfw stage: version=" + org.lwjgl.glfw.GLFW.glfwGetVersionString()
                    + " time=" + org.lwjgl.glfw.GLFW.glfwGetTime());
            org.lwjgl.glfw.GLFW.glfwPollEvents();
            System.out.println("glfw stage: jni calls ok");
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_VISIBLE, org.lwjgl.glfw.GLFW.GLFW_FALSE);
            // Same hints Minecraft uses: a 3.2 core context, not the driver default.
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE, org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE);
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT, org.lwjgl.glfw.GLFW.GLFW_TRUE);
            long handle = org.lwjgl.glfw.GLFW.glfwCreateWindow(320, 240, "b1", 0L, 0L);
            System.out.println("glfw stage: window=" + handle);
            if (handle != 0L) {
                org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(handle);
                System.out.println("glfw stage: current=" + org.lwjgl.glfw.GLFW.glfwGetCurrentContext()
                        + " ctxVersion=" + org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(handle, org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR)
                        + "." + org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(handle, org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR)
                        + " clientApi=" + org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(handle, org.lwjgl.glfw.GLFW.GLFW_CLIENT_API));
                org.lwjgl.glfw.GLFW.glfwDestroyWindow(handle);
            }
            org.lwjgl.glfw.GLFW.glfwTerminate();
            return;
        }
        // NOTE: do not touch GLFW before Minecraft initialises it: any call sets a GLFW error that
        // Minecraft's own GLX._initGlfw turns into an IllegalStateException.
        net.minecraft.client.main.Main.main(clientArgs);
    }

    /**
     * Minecraft installs GL capabilities after creating its window. In the static runtime the
     * early window is gone, so some GL use can happen before that; the function table is then a
     * set of "missing function" stubs and the first call jumps into nowhere. This watchdog waits
     * for a current context and installs the capabilities itself, whichever side gets there first.
     */
    private static void startCapabilitiesWatchdog() {
        Thread watchdog = new Thread(() -> {
            for (int attempt = 0; attempt < 1200; attempt++) {
                try {
                    long context = org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
                    if (context != 0L) {
                        try {
                            org.lwjgl.opengl.GL.getCapabilities();
                        } catch (Throwable missing) {
                            org.lwjgl.opengl.GLCapabilities caps = org.lwjgl.opengl.GL.createCapabilities();
                            System.out.println("[b1] installed GL capabilities for context 0x"
                                    + Long.toHexString(context) + " OpenGL32=" + caps.OpenGL32);
                        }
                        return;
                    }
                    Thread.sleep(25L);
                } catch (InterruptedException interrupted) {
                    return;
                } catch (Throwable ignored) {
                    // glfw may not be initialised yet
                }
            }
        }, "b1-gl-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
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
        String glfw = System.getenv("B1_GLFW");
        if (glfw != null && !glfw.isEmpty()) {
            System.setProperty("org.lwjgl.glfw.libname", glfw);
            System.out.println("glfw: libname -> " + glfw);
        }
        System.setProperty("org.lwjgl.opengl.libname", osmesa);
        System.setProperty("org.lwjgl.opengl.osmesa.libname", osmesa);
        System.out.println("gl: libname -> " + osmesa);
        try {
            org.lwjgl.glfw.GLFW.glfwInitHint(org.lwjgl.glfw.GLFW.GLFW_PLATFORM, org.lwjgl.glfw.GLFW.GLFW_PLATFORM_NULL);
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_CONTEXT_CREATION_API,
                    org.lwjgl.glfw.GLFW.GLFW_OSMESA_CONTEXT_API);
            System.out.println("glfw: null platform + OSMesa context");
        } catch (Throwable error) {
            System.out.println("cannot configure headless glfw: " + error);
        }
        createProbeContext();
    }

    /**
     * GLFW owns the OSMesa context, so a hidden window is created up front and made current.
     * That is what the early window used to do for sodium's class initialisation.
     */
    private static void createProbeContext() {
        try {
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_VISIBLE, org.lwjgl.glfw.GLFW.GLFW_FALSE);
            if (!org.lwjgl.glfw.GLFW.glfwInit()) {
                System.out.println("glfw: init failed");
                return;
            }
            long window = org.lwjgl.glfw.GLFW.glfwCreateWindow(1280, 720, "b1-probe", 0L, 0L);
            if (window == 0L) {
                System.out.println("glfw: probe window creation failed");
                return;
            }
            org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(window);
            System.out.println("glfw: probe context current, GL = "
                    + org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION));
        } catch (Throwable error) {
            System.out.println("cannot create probe context: " + error);
        }
    }

    /**
     * Sodium runs GL calls from {@code VertexFormat}'s class initialiser, long before Minecraft
     * creates its window, because FML's early window would normally have made a context current.
     * Creating and binding an OSMesa context here gives those calls somewhere to go.
     */
}
