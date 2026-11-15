import java.util.List;

/**
 * B1 · N0.5 第一步：把 dump 出来的类图谱直接放到 classpath 上加载，
 * 完全不启动 ModLauncher / 模块层，看这些类是否自洽可加载。
 */
public final class N0Load {
    private static final List<String> TARGETS = List.of(
            "net.minecraft.SharedConstants",
            "net.minecraft.server.Main",
            "net.minecraft.world.level.Level",
            "net.minecraft.world.item.Item",
            "net.minecraft.network.Connection",
            "net.neoforged.neoforge.common.NeoForge",
            "net.neoforged.bus.api.IEventBus",
            "net.neoforged.fml.ModLoader",
            "org.spongepowered.asm.mixin.Mixin",
            "com.mojang.logging.LogUtils");

    public static void main(String[] args) {
        int ok = 0;
        int failed = 0;
        for (String name : TARGETS) {
            try {
                Class<?> type = Class.forName(name, false, N0Load.class.getClassLoader());
                System.out.println("OK   " + name + "  (" + type.getDeclaredMethods().length + " methods)");
                ok++;
            } catch (Throwable error) {
                System.out.println("FAIL " + name + "  -> " + error);
                failed++;
            }
        }
        System.out.println("loaded " + ok + " / " + TARGETS.size() + " (failed " + failed + ")");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
