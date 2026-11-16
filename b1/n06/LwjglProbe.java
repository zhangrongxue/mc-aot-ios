import org.lwjgl.system.MemoryUtil;

/**
 * B1 LWJGL AOT probe.
 *
 * <p>LWJGL 3.3.3's MemoryUtil reads java.nio.Buffer field offsets through sun.misc.Unsafe, which
 * native-image does not provide ("Failed to find field offset in class"). Newer LWJGL releases
 * changed that code, so this probe builds a tiny image against the candidate version and asks
 * only whether MemoryUtil initialises at all.
 */
public final class LwjglProbe {
    public static void main(String[] args) {
        System.out.println("B1 LWJGL AOT probe");
        System.out.println("version: " + org.lwjgl.Version.getVersion());
        System.out.println("allocator: " + MemoryUtil.getAllocator(false));
        java.nio.ByteBuffer buffer = MemoryUtil.memAlloc(16);
        buffer.putInt(0, 0x12345678);
        System.out.println("buffer address readable: " + (MemoryUtil.memAddress(buffer) != 0));
        System.out.println("value: " + Integer.toHexString(buffer.getInt(0)));
        MemoryUtil.memFree(buffer);
        System.out.println("probe ok");
    }
}
