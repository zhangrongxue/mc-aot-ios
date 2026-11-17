import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

/**
 * Minimal GLFW/context probe: nothing but LWJGL, so a crash here is the native image itself
 * rather than anything Minecraft brings along.
 */
public final class GlfwProbe {
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "main";
        if (mode.equals("thread")) {
            Thread worker = new Thread(null, () -> run(), "probe", 256L * 1024L * 1024L);
            worker.start();
            worker.join();
        } else {
            run();
        }
    }

    private static void run() {
        String natives = System.getenv("B1_NATIVES");
        if (natives != null) {
            System.setProperty("org.lwjgl.librarypath", natives);
            System.setProperty("java.library.path", natives);
        }
        System.out.println("probe: init=" + GLFW.glfwInit());
        System.out.println("probe: version=" + GLFW.glfwGetVersionString());
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(320, 240, "probe", 0L, 0L);
        System.out.println("probe: window=" + window);
        if (window == 0L) {
            return;
        }
        GLFW.glfwMakeContextCurrent(window);
        System.out.println("probe: current=" + GLFW.glfwGetCurrentContext());
        try {
            // GL.create() only loads the library; createCapabilities() builds the function table
            // for the context that is current on *this* thread, which is what GL11 needs.
            org.lwjgl.opengl.GLCapabilities caps = GL.createCapabilities();
            System.out.println("probe: capabilities ok, OpenGL32=" + caps.OpenGL32
                    + " version=" + caps.OpenGL11 + "/" + caps.OpenGL45);
        } catch (Throwable error) {
            System.out.println("probe: createCapabilities failed: " + error);
        }
        System.out.println("probe: GL_VERSION=" + GL11.glGetString(GL11.GL_VERSION));
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        System.out.println("probe: ok");
    }
}
