package com.lyhyy2007.commandcontrol.client;

import com.lyhyy2007.commandcontrol.CommandControl;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import com.lyhyy2007.commandcontrol.GuardPreset;
import com.lyhyy2007.commandcontrol.GuardState;
import com.lyhyy2007.commandcontrol.config.CommandControlConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * Cloth Config 配置界面（<b>仅客户端</b>）。
 *
 * <p>设计要点：这个界面不是“第二份配置”，而是直接用
 * {@code ConfigBuilder} 读写 {@link CommandControlConfig} 里那套 {@code ModConfigSpec}。
 * 保存时先落盘（{@code SPEC.save()}）再刷新 {@link GuardState}，因此和 Configured、
 * 配置文件、{@code /commandcontrol} 三条入口共享同一份数据，不会互相覆盖。
 *
 * <p>与 Configured 的分工：Configured 会自动为使用 NeoForge 配置系统的模组生成菜单，
 * 本类只是额外提供一个 Cloth 风格的入口（例如没装 Configured 时也能在模组列表里点“配置”）。
 */
public final class CommandControlClothScreen {

    private CommandControlClothScreen() {}

    /** 构建配置界面。{@code parent} 用作返回按钮的目标（模组列表界面）。 */
    public static Screen create(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("Command Control 命令守卫"))
                .setSavingRunnable(() -> {
                    CommandControlConfig.save();
                    GuardState.refresh();
                    CommandControl.LOGGER.info("[{}] 通过 Cloth 界面保存配置，当前启用组：{}",
                            CommandControl.MODID, GuardState.current().preset().displayName());
                });

        ConfigEntryBuilder entries = builder.entryBuilder();

        // ------------------------------------------------------------ 常规
        ConfigCategory general = builder.getOrCreateCategory(Component.literal("常规"));
        general.setDescription(new FormattedText[] {
                Component.literal("所有者：" + CommandControl.OWNER + "　程序提供：" + CommandControl.PROVIDER
                        + "　v" + CommandControl.VERSION),
                Component.literal("默认 bypassPermissionLevel = 5：开作弊（等级 4）的玩家同样受限制；"),
                Component.literal("专用服务器请优先用 /commandcontrol 命令或服务器端配置文件；此界面编辑的是本地 config/command-control-common.toml。")
        });

        general.addEntry(entries
                .startBooleanToggle(Component.literal("总开关"), CommandControlConfig.ENABLED.get())
                .setDefaultValue(true)
                .setTooltip(Component.literal("关闭后 Command Control 完全不干预任何命令。"))
                .setSaveConsumer(value -> CommandControlConfig.ENABLED.set(value))
                .build());

        general.addEntry(entries
                .startEnumSelector(Component.literal("启用组"), GuardPreset.class, CommandControlConfig.PRESET.get())
                .setDefaultValue(GuardPreset.DISABLED)
                .setEnumNameProvider(value -> {
                    GuardPreset preset = (GuardPreset) value;
                    return Component.literal(preset.id() + ". " + preset.displayName());
                })
                .setTooltipSupplier(value -> {
                    GuardPreset preset = (GuardPreset) value;
                    return java.util.Optional.of(new Component[] {
                            Component.literal(preset.description()),
                            Component.literal("最高放行权限等级：" + preset.highestAllowedLevel())
                    });
                })
                .setSaveConsumer(value -> CommandControlConfig.PRESET.set(value))
                .build());

        // -------------------------------------------------------- 管辖范围
        ConfigCategory scope = builder.getOrCreateCategory(Component.literal("管辖范围"));
        scope.setDescription(new FormattedText[] {
                Component.literal("四个游戏模式默认全部纳入管辖（生存 / 创造 / 冒险 / 旁观），不再只针对冒险模式。"),
                Component.literal("模式切换保护会拦住 F3+F4、/gamemode 以及其它模组调用 setGameMode；切到旁观模式放行。")
        });

        scope.addEntry(entries
                .startBooleanToggle(Component.literal("管辖生存模式"), CommandControlConfig.RESTRICT_SURVIVAL.get())
                .setDefaultValue(true)
                .setSaveConsumer(value -> CommandControlConfig.RESTRICT_SURVIVAL.set(value))
                .build());
        scope.addEntry(entries
                .startBooleanToggle(Component.literal("管辖创造模式"), CommandControlConfig.RESTRICT_CREATIVE.get())
                .setDefaultValue(true)
                .setTooltip(Component.literal("开着作弊的房主通常处于创造模式，这个开关决定是否连他一起管。"))
                .setSaveConsumer(value -> CommandControlConfig.RESTRICT_CREATIVE.set(value))
                .build());
        scope.addEntry(entries
                .startBooleanToggle(Component.literal("管辖冒险模式"), CommandControlConfig.RESTRICT_ADVENTURE.get())
                .setDefaultValue(true)
                .setSaveConsumer(value -> CommandControlConfig.RESTRICT_ADVENTURE.set(value))
                .build());
        scope.addEntry(entries
                .startBooleanToggle(Component.literal("管辖旁观模式"), CommandControlConfig.RESTRICT_SPECTATOR.get())
                .setDefaultValue(true)
                .setSaveConsumer(value -> CommandControlConfig.RESTRICT_SPECTATOR.set(value))
                .build());
        scope.addEntry(entries
                .startBooleanToggle(Component.literal("禁止切换游戏模式（F3+F4）"),
                        CommandControlConfig.PROTECT_GAME_MODE_SWITCH.get())
                .setDefaultValue(true)
                .setTooltip(Component.literal("被管辖的玩家无法通过 F3+F4、/gamemode 或其它模组改变游戏模式。"))
                .setSaveConsumer(value -> CommandControlConfig.PROTECT_GAME_MODE_SWITCH.set(value))
                .build());

        // ------------------------------------------------------------ 列表
        ConfigCategory lists = builder.getOrCreateCategory(Component.literal("自定义列表"));
        lists.setDescription(new FormattedText[] {
                Component.literal("支持单个命令与带参数的命令：give / give @a diamond / tp * 0 0 / say hello **"),
                Component.literal("* 匹配任意一个词元；** 只能放在末尾，匹配其后全部词元。忽略大小写。")
        });

        lists.addEntry(entries
                .startStrList(Component.literal("禁用列表"),
                        new ArrayList<>(CommandControlConfig.DISABLED_COMMANDS.get()))
                .setDefaultValue(List.of())
                .setTooltip(Component.literal("命中即拦截；启用列表、白名单、bypassPermissionLevel 仍然优先。"))
                .setSaveConsumer(value -> CommandControlConfig.DISABLED_COMMANDS.set(value))
                .build());

        lists.addEntry(entries
                .startStrList(Component.literal("启用列表"),
                        new ArrayList<>(CommandControlConfig.ENABLED_COMMANDS.get()))
                .setDefaultValue(List.of())
                .setTooltip(Component.literal("命中即放行，优先级高于禁用列表与启用组。"))
                .setSaveConsumer(value -> CommandControlConfig.ENABLED_COMMANDS.set(value))
                .build());

        lists.addEntry(entries
                .startStrList(Component.literal("白名单玩家"),
                        new ArrayList<>(CommandControlConfig.WHITELIST_PLAYERS.get()))
                .setDefaultValue(List.of())
                .setTooltip(Component.literal("填用户名或 UUID；白名单玩家绕过全部限制。"))
                .setSaveConsumer(value -> CommandControlConfig.WHITELIST_PLAYERS.set(value))
                .build());

        // ------------------------------------------------------------ 权限
        ConfigCategory permissions = builder.getOrCreateCategory(Component.literal("权限与判定"));
        permissions.setDescription(new FormattedText[] {
                Component.literal("权限等级：0=所有玩家 1=Moderator 2=Gamemaster 3=Admin 4=Owner（1.21.1 原版已无等级 1 命令）。")
        });

        permissions.addEntry(entries
                .startIntSlider(Component.literal("绕过所需权限等级"),
                        CommandControlConfig.BYPASS_PERMISSION_LEVEL.get(), 0, 5)
                .setDefaultValue(5)
                .setTooltip(Component.literal("5（默认）= 谁都不放行，开作弊的玩家照样受限；4 = 服主/主机/OP 放行。"))
                .setSaveConsumer(value -> CommandControlConfig.BYPASS_PERMISSION_LEVEL.set(value))
                .build());

        permissions.addEntry(entries
                .startIntSlider(Component.literal("未知命令兜底等级"),
                        CommandControlConfig.UNKNOWN_COMMAND_LEVEL.get(), 0, 5)
                .setDefaultValue(2)
                .setTooltip(Component.literal("未收录命令（含模组命令）按此等级判定；命令树探测成功时以探测结果为准。"))
                .setSaveConsumer(value -> CommandControlConfig.UNKNOWN_COMMAND_LEVEL.set(value))
                .build());

        permissions.addEntry(entries
                .startBooleanToggle(Component.literal("命令树探测"),
                        CommandControlConfig.USE_DISPATCHER_PROBE.get())
                .setDefaultValue(true)
                .setTooltip(Component.literal("对未收录命令探测其真实权限等级（伪造不同等级的命令源测试命令树）。"))
                .setSaveConsumer(value -> CommandControlConfig.USE_DISPATCHER_PROBE.set(value))
                .build());

        permissions.addEntry(entries
                .startBooleanToggle(Component.literal("放行控制台 / RCON"),
                        CommandControlConfig.ALLOW_CONSOLE_SOURCES.get())
                .setDefaultValue(true)
                .setTooltip(Component.literal("服主在服务器控制台 / RCON 上管理时的兜底入口，建议保持开启。"))
                .setSaveConsumer(value -> CommandControlConfig.ALLOW_CONSOLE_SOURCES.set(value))
                .build());

        permissions.addEntry(entries
                .startBooleanToggle(Component.literal("放行命令方块"),
                        CommandControlConfig.ALLOW_COMMAND_BLOCKS.get())
                .setDefaultValue(false)
                .setTooltip(Component.literal("默认关闭：创造模式玩家不能靠命令方块绕过限制。老地图依赖命令方块时再打开。"))
                .setSaveConsumer(value -> CommandControlConfig.ALLOW_COMMAND_BLOCKS.set(value))
                .build());

        // ------------------------------------------------------------ 提示语
        ConfigCategory messages = builder.getOrCreateCategory(Component.literal("提示语"));
        messages.addEntry(entries
                .startStrField(Component.literal("拦截提示"), CommandControlConfig.DENIED_MESSAGE.get())
                .setDefaultValue("该命令已被 Command Control 禁用：{command}（当前启用组：{preset}）")
                .setTooltip(Component.literal("占位符：{command} = 完整命令，{root} = 命令名，{preset} = 当前启用组。"))
                .setSaveConsumer(value -> CommandControlConfig.DENIED_MESSAGE.set(value))
                .build());
        messages.addEntry(entries
                .startStrField(Component.literal("模式切换拦截提示"),
                        CommandControlConfig.GAME_MODE_DENIED_MESSAGE.get())
                .setDefaultValue("游戏模式切换已被 Command Control 禁用")
                .setSaveConsumer(value -> CommandControlConfig.GAME_MODE_DENIED_MESSAGE.set(value))
                .build());

        return builder.build();
    }
}
