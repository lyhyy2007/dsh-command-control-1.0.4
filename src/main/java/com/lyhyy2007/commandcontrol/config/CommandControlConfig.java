package com.lyhyy2007.commandcontrol.config;

import com.lyhyy2007.commandcontrol.GuardPreset;
import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Command Control 的配置定义（NeoForge 原生 {@link ModConfigSpec}，类型 COMMON）。
 *
 * <p>为什么用 ModConfigSpec 而不是 Cloth Config 自己的配置文件？
 * <ul>
 *   <li>COMMON 配置在单人、局域网、专用服务器三种环境下都由“逻辑服务端”加载，
 *       命令拦截发生在服务端，读到的就是服务端自己的值；</li>
 *   <li>Configured 能自动为使用受支持配置系统（NeoForge/Forge Config）的模组生成游戏内菜单；</li>
 *   <li>Cloth Config 只作为“另一个编辑入口”（见 {@code client.CommandControlClothScreen}），
 *       读写同一份 ModConfigSpec，不会出现两套配置数据不一致的问题。</li>
 * </ul>
 *
 * <p>文件位置：{@code config/command-control-common.toml}
 *
 * <p>1.0.1 的默认值变化（针对“装了没生效”这一反馈）：
 * <ul>
 *   <li>{@code preset} 默认从 {@code NO_RESTRICTION} 改为 {@code DISABLED}（组 1）——装上即生效；</li>
 *   <li>{@code bypassPermissionLevel} 默认从 4 改为 5——开作弊（等级 4）也不再放行任何人；</li>
 *   <li>取消“仅在冒险模式生效”，改为四个游戏模式各一个开关，默认全开（生存/创造/冒险/旁观都管）；</li>
 *   <li>新增 {@code protectGameModeSwitch}——拦住 F3+F4 与任何游戏模式切换。</li>
 * </ul>
 */
public final class CommandControlConfig {

    /**
     * 当前配置结构版本。加载到更旧的文件时，{@code CommandControl} 会做一次性迁移
     * （NightConfig 只补新键、不覆盖旧值，所以必须显式迁移）。
     */
    public static final int CURRENT_CONFIG_VERSION = 1;

    public static final ModConfigSpec SPEC;

    // ---------------------------------------------------------------- general
    /** 配置文件年代标记：0 = 1.0.0/1.0.1 时代，1 = 1.0.2 起。 */
    public static final ModConfigSpec.IntValue CONFIG_VERSION;
    /** 总开关。 */
    public static final ModConfigSpec.BooleanValue ENABLED;
    /** 当前启用的组（1~4）。 */
    public static final ModConfigSpec.EnumValue<GuardPreset> PRESET;

    /** 管辖哪些游戏模式：生存。 */
    public static final ModConfigSpec.BooleanValue RESTRICT_SURVIVAL;
    /** 管辖哪些游戏模式：创造。 */
    public static final ModConfigSpec.BooleanValue RESTRICT_CREATIVE;
    /** 管辖哪些游戏模式：冒险。 */
    public static final ModConfigSpec.BooleanValue RESTRICT_ADVENTURE;
    /** 管辖哪些游戏模式：旁观。 */
    public static final ModConfigSpec.BooleanValue RESTRICT_SPECTATOR;
    /** 是否禁止玩家切换游戏模式（F3+F4、/gamemode、其它模组调用 setGameMode）。 */
    public static final ModConfigSpec.BooleanValue PROTECT_GAME_MODE_SWITCH;

    // ------------------------------------------------------------------ lists
    /** 自定义禁用列表。 */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DISABLED_COMMANDS;
    /** 自定义启用列表（优先级高于禁用列表与启用组）。 */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ENABLED_COMMANDS;
    /** 白名单玩家（用户名或 UUID，绕过全部限制）。 */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> WHITELIST_PLAYERS;

    // ------------------------------------------------------------ permissions
    /** 权限等级 >= 此值的命令源直接放行。默认 5 = 谁都不放行。 */
    public static final ModConfigSpec.IntValue BYPASS_PERMISSION_LEVEL;
    /** 未收录命令（含模组命令）的兜底权限等级。 */
    public static final ModConfigSpec.IntValue UNKNOWN_COMMAND_LEVEL;
    /** 是否放行控制台 / RCON（服主最后的兜底入口）。 */
    public static final ModConfigSpec.BooleanValue ALLOW_CONSOLE_SOURCES;
    /** 是否放行命令方块 / 命令方块矿车。 */
    public static final ModConfigSpec.BooleanValue ALLOW_COMMAND_BLOCKS;
    /** 是否用命令树探测模组命令的真实权限等级。 */
    public static final ModConfigSpec.BooleanValue USE_DISPATCHER_PROBE;
    /** 是否启用命令树强制闸门（第一层：解析期截断）。 */
    public static final ModConfigSpec.BooleanValue ENABLE_DISPATCHER_GATE;

    // --------------------------------------------------------------- messages
    /** 命令被拦截时的提示语。 */
    public static final ModConfigSpec.ConfigValue<String> DENIED_MESSAGE;
    /** 游戏模式切换被拦截时的提示语。 */
    public static final ModConfigSpec.ConfigValue<String> GAME_MODE_DENIED_MESSAGE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Command Control —— 服务端命令守卫：按“启用组 + 自定义列表”决定哪些命令可用。",
                        "所有者：lyhyy2007　程序提供：deepseek")
                .push("general");
        ENABLED = builder
                .comment("总开关。false 时本模组完全不干预任何命令。")
                .define("enabled", true);
        CONFIG_VERSION = builder
                .comment("配置结构版本，由模组自动维护，请勿手工改小。",
                        "0 = 1.0.0/1.0.1 时代的旧文件；升级到 1.0.2 时会把旧默认值一次性迁移为强制生效的设定。")
                .defineInRange("configVersion", 0, 0, Integer.MAX_VALUE);
        PRESET = builder
                .comment("当前启用的组（1.0.4 起只有两个，默认 DISABLED = 禁用指令）：",
                        "DISABLED      = 禁用指令：只放行权限等级 0 的命令，其余在解析期被截断（补全里也不出现）",
                        "NO_RESTRICTION= 不禁用指令：命令树完全不动，所有命令照常可见可用（自定义禁用列表仍生效）")
                .defineEnum("preset", GuardPreset.DISABLED);
        RESTRICT_SURVIVAL = builder
                .comment("管辖生存模式的玩家。")
                .define("restrictSurvival", true);
        RESTRICT_CREATIVE = builder
                .comment("管辖创造模式的玩家（开着作弊的房主也在这里管）。")
                .define("restrictCreative", true);
        RESTRICT_ADVENTURE = builder
                .comment("管辖冒险模式的玩家。")
                .define("restrictAdventure", true);
        RESTRICT_SPECTATOR = builder
                .comment("管辖旁观模式的玩家。")
                .define("restrictSpectator", true);
        PROTECT_GAME_MODE_SWITCH = builder
                .comment("禁止被管辖的玩家切换游戏模式：F3+F4、/gamemode 以及其它模组调用 setGameMode 都会被拦。",
                        "例外：切到旁观模式（例如硬核死亡后的强制旁观）一律放行。")
                .define("protectGameModeSwitch", true);
        builder.pop();

        builder.comment("自定义命令列表：支持单个命令与带参数的命令。",
                        "语法：give  或  give @a diamond  或  tp * 0 0  或  say hello **",
                        "* 匹配任意一个词元；** 只能放末尾，匹配其后全部词元。")
                .push("lists");
        DISABLED_COMMANDS = builder
                .comment("禁用列表：命中即拦截（白名单玩家与 bypassPermissionLevel 仍然优先放行）。")
                .defineListAllowEmpty("disabledCommands", List.of(), () -> "give", CommandControlConfig::isNotBlank);
        ENABLED_COMMANDS = builder
                .comment("启用列表：命中即放行，优先级高于禁用列表与启用组。")
                .defineListAllowEmpty("enabledCommands", List.of(), () -> "tp", CommandControlConfig::isNotBlank);
        WHITELIST_PLAYERS = builder
                .comment("白名单玩家：填用户名或 UUID，绕过全部限制（含启用组与模式切换限制）。")
                .defineListAllowEmpty("whitelistPlayers", List.of(), () -> "Steve", CommandControlConfig::isNotBlank);
        builder.pop();

        builder.comment("权限相关设置。").push("permissions");
        BYPASS_PERMISSION_LEVEL = builder
                .comment("权限等级 >= 此值的命令源直接放行。",
                        "5 = 谁都不放行（默认；开作弊的玩家等级是 4，照样受限）；",
                        "4 = 服主/联机主机/OP 放行；0 = 连控制台都受限。")
                .defineInRange("bypassPermissionLevel", 5, 0, 5);
        UNKNOWN_COMMAND_LEVEL = builder
                .comment("未收录命令（含模组命令）的兜底权限等级。组 1~3 按此判定，组 4 无视。")
                .defineInRange("unknownCommandLevel", 2, 0, 5);
        ALLOW_CONSOLE_SOURCES = builder
                .comment("放行服务器控制台与 RCON（服主在服务器上管理时的兜底入口，建议保持 true）。")
                .define("allowConsoleSources", true);
        ALLOW_COMMAND_BLOCKS = builder
                .comment("放行命令方块 / 命令方块矿车。默认 false：创造模式玩家也不能靠命令方块绕过限制。",
                        "老地图/机器依赖命令方块时再打开。")
                .define("allowCommandBlocks", false);
        USE_DISPATCHER_PROBE = builder
                .comment("对未收录命令用命令树探测真实权限等级；探测失败时用 unknownCommandLevel。")
                .define("useDispatcherProbe", true);
        ENABLE_DISPATCHER_GATE = builder
                .comment("第一层拦截：命令树强制闸门（解析期截断，被拦的命令在 Tab 补全里也不会出现）。",
                        "默认开启。若遇到兼容性问题可关掉，只保留 CommandEvent 执行前取消那一层（改完执行 /reload 生效）。")
                .define("enableDispatcherGate", true);
        builder.pop();

        builder.comment("提示语设置。").push("messages");
        DENIED_MESSAGE = builder
                .comment("被拦截时发给玩家的提示。占位符：{command} = 完整命令，{root} = 命令名，{preset} = 当前启用组。")
                .define("deniedMessage", "该命令已被 Command Control 禁用：{command}（当前启用组：{preset}）");
        GAME_MODE_DENIED_MESSAGE = builder
                .comment("游戏模式切换被拦截时的提示。")
                .define("gameModeDeniedMessage", "游戏模式切换已被 Command Control 禁用");
        builder.pop();

        SPEC = builder.build();
    }

    private CommandControlConfig() {}

    /** 列表元素校验：非空字符串。 */
    private static boolean isNotBlank(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    /** 把当前值写回 {@code config/command-control-common.toml}。 */
    public static void save() {
        SPEC.save();
    }
}
