/**
 * B1 client native-image smoke: pull the real client types into the closed world.
 */
public final class ClientSmoke {
    public static void main(String[] args) {
        System.out.println("B1 client native-image smoke");
        System.out.println(net.minecraft.client.Minecraft.class.getName());
        System.out.println(net.minecraft.client.gui.screens.TitleScreen.class.getName());
        System.out.println(net.minecraft.client.renderer.GameRenderer.class.getName());
        System.out.println(com.mojang.blaze3d.platform.Window.class.getName());
        System.out.println(net.neoforged.neoforge.client.ClientHooks.class.getName());
        System.out.println("ok");
    }
}
