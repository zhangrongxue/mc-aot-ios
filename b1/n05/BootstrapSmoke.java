/**
 * B1 static-runtime smoke: run Minecraft's own data bootstrap without ModLauncher.
 *
 * <p>If this links and prints registry counts in a native image, the transformed class graph is
 * good enough to initialise the game's registries with no module layer, no ModLauncher and no
 * JIT. That is the first real step of the static runtime (the client entry needs a GL context,
 * which CI does not have; the data bootstrap does not).
 */
public final class BootstrapSmoke {
    public static void main(String[] args) throws Exception {
        System.out.println("B1 static-runtime bootstrap smoke");
        long started = System.nanoTime();
        net.minecraft.server.Bootstrap.bootStrap();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        System.out.println("bootstrap ms=" + elapsedMs);
        System.out.println("blocks=" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.size());
        System.out.println("items=" + net.minecraft.core.registries.BuiltInRegistries.ITEM.size());
        System.out.println("entities=" + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.size());
        System.out.println("ok");
    }
}
