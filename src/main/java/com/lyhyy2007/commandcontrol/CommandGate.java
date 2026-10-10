package com.lyhyy2007.commandcontrol;

import com.mojang.brigadier.CommandDispatcher;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.rcon.RconConsoleSource;

/**
 * <b>第一层：命令树强制闸门</b>（只在“禁用指令”预设下装配）。
 *
 * <p>1.0.4 的关键行为：<b>“不禁用指令”模式下完全不碰命令树</b>。
 * 1.0.2/1.0.3 不管什么预设都包装节点，结果是连 {@code /tp}、{@code /time} 都从补全与解析里消失，
 * 即使在“不禁用”模式下也一样——这条路径彻底堵死：
 * {@link GuardState#shouldGateCommandTree()} 只有在
 * “总开关开 + 闸门开关开 + 预设 = 禁用指令”时才为 true。
 *
 * <p>实际的手术由 {@link CommandTreeGate} 完成（泛型、自检可覆盖、失败自动回滚），
 * 本类只负责 Minecraft 侧的判定与来源分类。
 */
public final class CommandGate {

    /** 已包装的根命令名（诊断用）。 */
    private static final Set<String> GATED_ROOTS = ConcurrentHashMap.newKeySet();
    /** 包装前的原始 requirement：权限探测必须用它，否则会递归回判定（1.0.2 的卡死根因）。 */
    private static final Map<String, Predicate<CommandSourceStack>> ORIGINAL_REQUIREMENTS = new ConcurrentHashMap<>();
    private static final AtomicInteger INSTALL_COUNT = new AtomicInteger();
    private static final AtomicInteger BLOCK_COUNT = new AtomicInteger();
    private static final AtomicInteger LOGGED = new AtomicInteger();
    private static final int LOG_LIMIT = 5;

    private CommandGate() {}

    /** 命令表建好时安装闸门；如果当前是“不禁用指令”模式则什么都不做。 */
    public static void install(CommandDispatcher<CommandSourceStack> dispatcher) {
        GATED_ROOTS.clear();
        ORIGINAL_REQUIREMENTS.clear();
        GuardState state = GuardState.current();
        if (!state.shouldGateCommandTree()) {
            CommandControl.LOGGER.info("[{}] 当前不包装命令树（总开关={}，闸门={}，预设={}）= 所有命令照常可见可用",
                    CommandControl.MODID, state.enabled() ? "开" : "关",
                    state.enableDispatcherGate() ? "开" : "关",
                    state.preset().displayName());
            return;
        }
        CommandTreeGate.Result<CommandSourceStack> result = CommandTreeGate.install(
                dispatcher, CommandGate::evaluate, Set.of(GuardState.MANAGE_COMMAND));
        if (result.installed()) {
            GATED_ROOTS.addAll(result.wrappedNames());
            ORIGINAL_REQUIREMENTS.putAll(result.originals());
            INSTALL_COUNT.incrementAndGet();
            CommandControl.LOGGER.info("[{}] 命令树强制闸门已安装：{}（第 {} 次安装）",
                    CommandControl.MODID, result.message(), INSTALL_COUNT.get());
        } else {
            CommandControl.LOGGER.error("[{}] 命令树闸门未安装（{}），本次不拦截解析期命令",
                    CommandControl.MODID, result.message());
        }
    }

    /** 包装前的原始 requirement（探测用）；没包装过则返回 null。 */
    public static Predicate<CommandSourceStack> originalRequirement(String root) {
        return ORIGINAL_REQUIREMENTS.get(CommandPattern.normalize(root));
    }

    /** 闸门判定入口（{@link CommandTreeGate} 的回调）：不可重入，异常放行。 */
    private static boolean evaluate(String root, CommandSourceStack source) {
        return ReentrancyGuard.call(() -> doEvaluate(root, source), () -> Boolean.TRUE);
    }

    private static boolean doEvaluate(String root, CommandSourceStack source) {
        try {
            GuardState state = GuardState.current();
            if (!state.shouldGateCommandTree()) {
                return true;
            }
            int level = CommandPermissions.resolve(root, source, state.useDispatcherProbe(),
                    state.unknownCommandLevel());
            GuardState.Verdict verdict = state.decideRoot(root, level, factsOf(source));
            if (verdict.allowed()) {
                return true;
            }
            BLOCK_COUNT.incrementAndGet();
            if (LOGGED.getAndIncrement() < LOG_LIMIT) {
                CommandControl.LOGGER.info("[{}] 闸门截断 /{}（{}，来源 {}）",
                        CommandControl.MODID, root, verdict.reason(), describeSource(source));
            }
            return false;
        } catch (Throwable t) {
            CommandControl.LOGGER.error("[{}] 闸门判定异常，已放行 /{}", CommandControl.MODID, root, t);
            return true;
        }
    }

    /** 从命令源构造判定事实（{@code CommandEvent} 层也复用这个方法）。 */
    public static GuardState.SourceFacts factsOf(CommandSourceStack source) {
        if (source == null) {
            return new GuardState.SourceFacts(false, true, false, false, true);
        }
        GuardState state = GuardState.current();
        boolean bypassed = source.hasPermission(state.bypassPermissionLevel());
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            boolean restricted = state.isRestrictedGameMode(player.gameMode.getGameModeForPlayer());
            return new GuardState.SourceFacts(true, restricted, state.isWhitelisted(player), bypassed, false);
        }
        return new GuardState.SourceFacts(false, true, false, bypassed, isConsoleLike(source));
    }

    private static boolean isConsoleLike(CommandSourceStack source) {
        return source.source instanceof MinecraftServer || source.source instanceof RconConsoleSource;
    }

    /** 诊断用：命令源的可读描述。 */
    public static String describeSource(CommandSourceStack source) {
        if (source == null) {
            return "null";
        }
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            return "玩家 " + player.getGameProfile().getName() + "（等级 " + permissionLevelOf(source) + "）";
        }
        return (isConsoleLike(source) ? "控制台/RCON" : "命令方块") + "（等级 " + permissionLevelOf(source) + "）";
    }

    /** 诊断用：向下探测命令源的权限等级（0~4，探测不到返回 -1）。 */
    public static int permissionLevelOf(CommandSourceStack source) {
        for (int level = 4; level >= 0; level--) {
            if (source.hasPermission(level)) {
                return level;
            }
        }
        return -1;
    }

    public static int gatedRootCount() {
        return GATED_ROOTS.size();
    }

    public static int installCount() {
        return INSTALL_COUNT.get();
    }

    public static int blockedCount() {
        return BLOCK_COUNT.get();
    }

    public static boolean isGated(String root) {
        return GATED_ROOTS.contains(CommandPattern.normalize(root));
    }
}
