package com.lyhyy2007.commandcontrol.client;

import com.lyhyy2007.commandcontrol.CommandControl;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * 客户端初始化：把 Cloth Config 界面挂到“模组列表 → 配置”按钮上。
 *
 * <p>用 {@code @EventBusSubscriber(value = Dist.CLIENT)} 限定只在客户端加载，
 * 专用服务器不会触碰任何客户端类；同时用 {@code ModList.isLoaded("cloth_config")} 判断前置，
 * 没装 Cloth Config 时直接跳过（Configured 和配置文件两条路依然可用）。
 *
 * <p>注意 NeoForge 21.1.x 的 {@code @EventBusSubscriber} 只有 {@code modid} 与 {@code value}（Dist[]），
 * 没有 {@code bus} / {@code dist} 属性——这里的写法是该版本唯一正确的形式。
 */
@EventBusSubscriber(modid = CommandControl.MODID, value = Dist.CLIENT)
public final class CommandControlClientSetup {

    private CommandControlClientSetup() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        if (!ModList.get().isLoaded("cloth_config")) {
            CommandControl.LOGGER.info("[{}] 未检测到 Cloth Config，跳过 Cloth 配置界面（Configured 与配置文件仍然可用）",
                    CommandControl.MODID);
            return;
        }
        // 先建好带类型的实例，避免 registerExtensionPoint(Class, T) 与 (Class, Supplier<T>) 的重载歧义。
        IConfigScreenFactory factory = (modContainer, parent) -> createScreen(parent);
        ModList.get().getModContainerById(CommandControl.MODID)
                .ifPresent(container -> container.registerExtensionPoint(IConfigScreenFactory.class, factory));
        CommandControl.LOGGER.info("[{}] 已注册 Cloth Config 配置界面", CommandControl.MODID);
    }

    private static Screen createScreen(Screen parent) {
        return CommandControlClothScreen.create(parent);
    }
}
