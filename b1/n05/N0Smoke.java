/**
 * B1 · N0.5 第二步：native-image 冒烟入口。
 *
 * 不启动 ModLauncher，只用类字面量把 NeoForge / Minecraft 的类型拉进封闭世界分析。
 * 能链接出来就说明这份类图谱可以被 AOT 消费；失败信息就是 N1 要补的配置清单。
 */
public final class N0Smoke {
    public static void main(String[] args) throws Exception {
        System.out.println("B1 N0.5 native-image smoke");
        System.out.println("shared constants: " + net.minecraft.SharedConstants.class.getName());
        System.out.println("level: " + net.minecraft.world.level.Level.class.getName());
        System.out.println("item: " + net.minecraft.world.item.Item.class.getName());
        System.out.println("connection: " + net.minecraft.network.Connection.class.getName());
        System.out.println("neoforge bus: " + net.neoforged.neoforge.common.NeoForge.class.getName());
        System.out.println("fml loader: " + net.neoforged.fml.ModLoader.class.getName());
        System.out.println("mixin: " + org.spongepowered.asm.mixin.Mixin.class.getName());
        System.out.println("ok");
    }
}
