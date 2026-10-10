package com.lyhyy2007.commandcontrol;

import com.lyhyy2007.commandcontrol.command.CommandControlCommand;
import com.mojang.brigadier.ParseResults;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 命令拦截的三层结构（1.0.2 重构）。
 *
 * <p><b>第一层：命令树强制闸门</b>（{@link CommandGate}）——在 {@code RegisterCommandsEvent} 里
 * 包装每个根命令的 requirement，判定不过的命令在<b>解析阶段</b>就被当成不存在：
 * 不进入执行流程、Tab 补全里也不出现，命令方块 / 告示牌 / 函数 / 直接调用 dispatcher 的模组同样被截断。
 *
 * <p><b>第二层：{@link CommandEvent} 执行前取消</b>——这一层拿得到完整命令行，
 * 负责参数级规则（{@code tp @e}、{@code time set day} 之类），并且在闸门被别的模组绕过时兜底。
 * 事件在 {@code Commands#performCommand} 里触发（位于主事件总线、仅逻辑服务端，实现
 * {@code ICancellableEvent}）；NeoForge 21.1.x 的 {@code CommandEvent} <b>没有</b> {@code getCommand()}，
 * 原始命令串从 {@code getParseResults().getReader().getString()} 取。
 *
 * <p><b>第三层：{@link PlayerEvent.PlayerChangeGameModeEvent}</b>——F3+F4 只发一个改模式的网络包，
 * 最终走 {@code ServerPlayer#setGameMode}，全程不经过命令系统，所以必须单独拦。
 *
 * <p>三层共用 {@link GuardState#decide} / {@link GuardState#decideRoot} 的同一套判定，结论一致。
 * 监听器由 {@code CommandControl} 在主类构造器里<b>显式注册</b>（不依赖注解扫描）。
 */
public final class CommandGuard {

    /** 第二层拦截次数。 */
    private static final AtomicInteger COMMAND_BLOCK_COUNT = new AtomicInteger();
    /** 第三层拦截次数。 */
    private static final AtomicInteger GAME_MODE_BLOCK_COUNT = new AtomicInteger();
    private static final AtomicInteger COMMAND_LOGGED = new AtomicInteger();
    private static final AtomicInteger GAME_MODE_LOGGED = new AtomicInteger();
    private static final int LOG_LIMIT = 5;

    private CommandGuard() {}

    /** 第一层：命令表建好时注册管理命令 + 清探测缓存 + 安装强制闸门。 */
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandControlCommand.register(event.getDispatcher());
        CommandPermissions.clearProbeCache();
        if (GuardState.current().enableDispatcherGate()) {
            CommandGate.install(event.getDispatcher());
        } else {
            CommandControl.LOGGER.info("[{}] 配置里关闭了命令树强制闸门，仅保留 CommandEvent 执行层拦截",
                    CommandControl.MODID);
        }
    }

    /** 第二层：执行前取消（含参数级判定）。 */
    public static void onCommand(CommandEvent event) {
        GuardState state = GuardState.current();
        if (!state.enabled()) {
            return;
        }
        ParseResults<CommandSourceStack> parse = event.getParseResults();
        if (parse == null || parse.getContext() == null) {
            return;
        }
        CommandSourceStack source = parse.getContext().getSource();
        List<String> tokens = tokensOf(parse);
        if (tokens.isEmpty()) {
            return;
        }
        String root = CommandPattern.normalize(tokens.get(0));
        // 管理命令始终放行：Brigadier 里它要求权限等级 3，普通玩家依然敲不动。
        if (state.isManageCommand(root)) {
            return;
        }
        int level = CommandPermissions.resolve(root, source, state.useDispatcherProbe(), state.unknownCommandLevel());
        GuardState.Verdict verdict = state.decide(tokens, level, CommandGate.factsOf(source));
        if (verdict.allowed()) {
            return;
        }
        event.setCanceled(true);
        COMMAND_BLOCK_COUNT.incrementAndGet();
        if (source != null && source.getPlayer() != null) {
            source.sendFailure(Component.literal(state.deniedMessageFor(tokens)));
        }
        if (COMMAND_LOGGED.getAndIncrement() < LOG_LIMIT) {
            CommandControl.LOGGER.info("[{}] 拦截命令 /{}（{}，来源 {}）",
                    CommandControl.MODID, String.join(" ", tokens), verdict.reason(),
                    CommandGate.describeSource(source));
        }
    }

    /** 第三层：拦住 F3+F4 / {@code /gamemode} / 其它模组的 {@code setGameMode}。 */
    public static void onGameModeChange(PlayerEvent.PlayerChangeGameModeEvent event) {
        GuardState state = GuardState.current();
        if (!state.enabled() || !state.protectGameModeSwitch()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        GameType currentMode = event.getCurrentGameMode();
        GameType targetMode = event.getNewGameMode();
        if (currentMode == targetMode) {
            return;
        }
        CommandSourceStack source = player.createCommandSourceStack();
        GuardState.SourceFacts facts = CommandGate.factsOf(source);
        if (!facts.player() || !facts.gameModeRestricted() || facts.whitelisted() || facts.bypassed()) {
            return;
        }
        // 切到旁观模式是“降级”（例如硬核死亡后的强制旁观），放行以免把玩家卡死
        if (targetMode == GameType.SPECTATOR) {
            return;
        }
        event.setCanceled(true);
        GAME_MODE_BLOCK_COUNT.incrementAndGet();
        player.displayClientMessage(Component.literal(state.gameModeDeniedMessage()), false);
        if (GAME_MODE_LOGGED.getAndIncrement() < LOG_LIMIT) {
            CommandControl.LOGGER.info("[{}] 拦截游戏模式切换：{} -> {}（玩家 {}）",
                    CommandControl.MODID, currentMode, targetMode, player.getGameProfile().getName());
        }
    }

    public static int blockedCommandCount() {
        return COMMAND_BLOCK_COUNT.get();
    }

    public static int blockedGameModeCount() {
        return GAME_MODE_BLOCK_COUNT.get();
    }

    /** 从 ParseResults 里取出原始命令串并切成词元。 */
    private static List<String> tokensOf(ParseResults<CommandSourceStack> parse) {
        // ParseResults#getContext() 返回的是 CommandContextBuilder（没有 getInput），
        // 原始命令串在 reader 里；reader 的字符串就是服务端收到的那一整条命令。
        String input = parse.getReader() == null ? null : parse.getReader().getString();
        return CommandPattern.tokenize(input);
    }
}
