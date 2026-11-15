/**
 * B1 client-side smoke: can the dumped client graph be loaded without ModLauncher?
 */
public final class ClientLoad {
    private static final String[] NAMES = {
            "net.minecraft.client.Minecraft",
            "net.minecraft.client.gui.screens.TitleScreen",
            "net.minecraft.client.renderer.GameRenderer",
            "com.mojang.blaze3d.platform.Window",
            "net.neoforged.neoforge.client.ClientHooks"
    };

    public static void main(String[] args) {
        int ok = 0;
        int fail = 0;
        for (String name : NAMES) {
            try {
                Class.forName(name, false, ClientLoad.class.getClassLoader());
                System.out.println("OK   " + name);
                ok++;
            } catch (Throwable error) {
                System.out.println("FAIL " + name + " -> " + error);
                fail++;
            }
        }
        System.out.println("client classes " + ok + "/" + NAMES.length + " (failed " + fail + ")");
    }
}
