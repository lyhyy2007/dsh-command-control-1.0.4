package com.lyhyy2007.commandcontrol;

import com.lyhyy2007.commandcontrol.config.CommandControlConfig;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/**
 * Command Control 主类（1.0.2）。
 *
 * <p>职责：
 * <ul>
 *   <li>注册 COMMON 配置（{@code config/command-control-common.toml}）；</li>
 *   <li><b>显式</b>把三层拦截注册到主事件总线（不依赖 {@code @EventBusSubscriber} 注解扫描）；</li>
 *   <li>加载/重载配置时做<b>版本迁移</b>并刷新 {@link GuardState} 快照；</li>
 *   <li>启动时把“当前到底拦不拦得住”打进日志，避免再出现“装了却静默不生效”。</li>
 * </ul>
 *
 * <p>为什么需要配置迁移：NightConfig 加载已有配置文件时**只补缺失的键，不会用新默认值覆盖旧值**。
 * 1.0.0 写出的文件里 {@code preset = "NO_RESTRICTION"}、{@code bypassPermissionLevel = 4} 会被一直沿用，
 * 于是升级到 1.0.1 也“看起来没生效”。1.0.2 用 {@code general.configVersion} 判断文件年代，
 * 旧文件一次性迁移到强制生效的设定（并 WARN 说明改了什么、怎么改回去）。
 *
 * <p>所有者：lyhyy2007　程序提供：deepseek
 */
@Mod(CommandControl.MODID)
public final class CommandControl {

    public static final String MODID = "command_control";
    /** 模组所有者（署名）。 */
    public static final String OWNER = "lyhyy2007";
    /** 程序提供者（署名）。 */
    public static final String PROVIDER = "deepseek";
    /** 当前版本，仅用于状态输出与日志（版本号本体在 gradle.properties / mods.toml）。 */
    public static final String VERSION = "1.0.4";

    public static final Logger LOGGER = LogUtils.getLogger();

    private static final String CONFIG_FILE_NAME = "command-control-common.toml";

    public CommandControl(IEventBus modEventBus, ModContainer modContainer) {
        // COMMON 配置：单人 / 局域网 / 专用服务器都由逻辑服务端加载，拦截时读到的就是服务端自己的值。
        modContainer.registerConfig(ModConfig.Type.COMMON, CommandControlConfig.SPEC, CONFIG_FILE_NAME);

        // 配置事件在主事件总线上是拿不到的：ModConfigEvent 是 IModBusEvent，只在 mod 事件总线。
        modEventBus.addListener(this::onConfigLoading);
        modEventBus.addListener(this::onConfigReloading);

        // 三层拦截：显式注册，避免任何注解扫描/分发层面的意外。
        NeoForge.EVENT_BUS.addListener(CommandGuard::onRegisterCommands); // 第一层：装强制闸门
        NeoForge.EVENT_BUS.addListener(CommandGuard::onCommand);          // 第二层：执行前取消
        NeoForge.EVENT_BUS.addListener(CommandGuard::onGameModeChange);   // 第三层：拦 F3+F4

        // 服务器起来后再刷一次，保证此时配置一定已加载。
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);

        GuardState.refresh();
        LOGGER.info("Command Control {} 已加载（所有者 {}，程序提供 {}）——三层拦截：命令树闸门 / CommandEvent / 模式切换",
                VERSION, OWNER, PROVIDER);
        warnIfPermissive();
    }

    private void onConfigLoading(ModConfigEvent.Loading event) {
        if (isOurConfig(event)) {
            migrateConfigIfNeeded();
            GuardState.refresh();
            logState("配置已加载");
            warnIfPermissive();
        }
    }

    private void onConfigReloading(ModConfigEvent.Reloading event) {
        if (isOurConfig(event)) {
            migrateConfigIfNeeded();
            GuardState.refresh();
            logState("配置已重载");
            warnIfPermissive();
        }
    }

    private boolean isOurConfig(ModConfigEvent event) {
        ModConfig config = event.getConfig();
        return config != null && config.getSpec() == CommandControlConfig.SPEC;
    }

    private void onServerStarted(ServerStartedEvent event) {
        migrateConfigIfNeeded();
        GuardState.refresh();
        logState("服务器已启动");
        warnIfPermissive();
    }

    /**
     * 旧配置文件的一次性迁移：把 1.0.0 时代的宽松默认值换成 1.0.1+ 的强制默认值。
     *
     * <p>只迁移“仍然是旧默认值”的键，不动用户明确改过的其它键；迁移后写回文件并盖上版本号。
     */
    private void migrateConfigIfNeeded() {
        try {
            int stored = CommandControlConfig.CONFIG_VERSION.get();
            if (stored >= CommandControlConfig.CURRENT_CONFIG_VERSION) {
                return;
            }
            List<String> changes = new ArrayList<>();
            if (CommandControlConfig.PRESET.get() == GuardPreset.NO_RESTRICTION) {
                CommandControlConfig.PRESET.set(GuardPreset.DISABLED);
                changes.add("preset: NO_RESTRICTION -> DISABLED（组 1，装上即生效）");
            }
            int bypass = CommandControlConfig.BYPASS_PERMISSION_LEVEL.get();
            if (bypass <= 4) {
                CommandControlConfig.BYPASS_PERMISSION_LEVEL.set(5);
                changes.add("bypassPermissionLevel: " + bypass + " -> 5（开作弊的玩家也受限）");
            }
            CommandControlConfig.CONFIG_VERSION.set(CommandControlConfig.CURRENT_CONFIG_VERSION);
            CommandControlConfig.save();
            if (changes.isEmpty()) {
                LOGGER.info("[{}] 配置版本已标记为 {}（无需改动任何旧值）",
                        MODID, CommandControlConfig.CURRENT_CONFIG_VERSION);
            } else {
                LOGGER.warn("[{}] 检测到旧版配置（configVersion={}），已迁移为强制生效的设定：{}。"
                                + "如果这是你有意为之，用 /commandcontrol 或直接改配置文件改回去。",
                        MODID, stored, String.join("；", changes));
            }
            GuardState.refresh();
        } catch (IllegalStateException notLoadedYet) {
            // 配置还没加载，等下一次事件
        }
    }

    /** 当前配置什么都拦不住时大声提醒。 */
    private void warnIfPermissive() {
        GuardState state = GuardState.current();
        if (!state.ready()) {
            return;
        }
        if (state.blocksNothing()) {
            LOGGER.warn("[{}] 当前配置不会拦截任何命令：总开关={}，启用组={}.{}，禁用列表={} 条。"
                            + "要让守卫生效：/commandcontrol preset 1（或把 preset 改成 DISABLED）。",
                    MODID, state.enabled() ? "开" : "关",
                    state.preset().id(), state.preset().displayName(), state.disabledPatterns().size());
        }
    }

    /** 把当前生效状态打进日志，方便确认“到底有没有生效”。 */
    private void logState(String reason) {
        GuardState state = GuardState.current();
        LOGGER.info("[{}] {}：总开关={}，启用组={}.{}，管辖模式={}，模式切换保护={}，"
                        + "绕过等级={}（5=谁都不放行），放行控制台={}，放行命令方块={}，"
                        + "闸门已装根命令={} 个，闸门拦截={} 次，命令拦截={} 次，模式切换拦截={} 次",
                MODID, reason,
                state.enabled() ? "开" : "关",
                state.preset().id(), state.preset().displayName(),
                state.restrictedModeSummary(),
                state.protectGameModeSwitch() ? "开" : "关",
                state.bypassPermissionLevel(),
                state.allowConsoleSources(),
                state.allowCommandBlocks(),
                CommandGate.gatedRootCount(),
                CommandGate.blockedCount(),
                CommandGuard.blockedCommandCount(),
                CommandGuard.blockedGameModeCount());
    }
}
