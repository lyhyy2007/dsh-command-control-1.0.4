package com.lyhyy2007.commandcontrol;

import com.mojang.brigadier.CommandDispatcher;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;

/**
 * 命令根名 → 所需权限等级（0~4）。
 *
 * <p><b>数据来源</b>：本表由 Minecraft 1.21.1（NeoForge 21.1.233 反编译源码）逐个命令类核对得出：
 * {@code net/minecraft/server/commands/*.java} 与 {@code net/minecraft/commands/Commands.java} 中的
 * {@code .requires(source -> source.hasPermission(n))}。原版 1.21.1 已经没有权限等级 1 的命令，
 * {@code /seed} 是唯一特例：集成服务器（单人/局域网）对所有人开放，专用服务器需要等级 2，
 * 这里按 {@link #resolve} 的运行时判断处理。
 *
 * <p>模组新增命令不在表里，默认按配置项 {@code unknownCommandLevel} 处理；
 * 打开 {@code useDispatcherProbe} 时，会用命令树里根节点的 requirement 谓词做一次
 * “伪造不同权限等级的命令源”探测，取最小可用等级（探测失败才回落到兜底值）。
 */
public final class CommandPermissions {

    public static final int LEVEL_ALL = Commands.LEVEL_ALL;
    public static final int LEVEL_MODERATORS = Commands.LEVEL_MODERATORS;
    public static final int LEVEL_GAMEMASTERS = Commands.LEVEL_GAMEMASTERS;
    public static final int LEVEL_ADMINS = Commands.LEVEL_ADMINS;
    public static final int LEVEL_OWNERS = Commands.LEVEL_OWNERS;

    /** 探测/查表都失败时的标记。 */
    public static final int UNKNOWN = -1;

    /** 权限等级 0：任何玩家都能用的命令（源码中根节点没有 requires）。 */
    private static final String[] LEVEL_0 = {
            "help", "list", "me", "msg", "tell", "w", "teammsg", "tm", "trigger"
    };

    /** 权限等级 2（Gamemaster）。 */
    private static final String[] LEVEL_2 = {
            "advancement", "attribute", "bossbar", "clear", "clone", "damage", "data", "datapack",
            "debugmobspawning", "debugpath", "defaultgamemode", "difficulty", "effect", "enchant",
            "execute", "experience", "fill", "fillbiome", "forceload", "function", "gamemode",
            "gamerule", "give", "item", "kill", "locate", "loot", "particle", "place", "playsound",
            "random", "recipe", "reload", "return", "ride", "say", "schedule", "scoreboard",
            "serverpack", "setblock", "setworldspawn", "spawn_armor_trims", "spawnpoint", "spectate",
            "spreadplayers", "stopsound", "summon", "tag", "team", "teleport", "tellraw", "time",
            "title", "tp", "warden_spawn_tracker", "weather", "worldborder", "xp"
    };

    /** 权限等级 3（Admin）。 */
    private static final String[] LEVEL_3 = {
            "ban", "ban-ip", "banlist", "debug", "debugconfig", "deop", "kick", "op", "pardon",
            "pardon-ip", "raid", "setidletimeout", "tick", "transfer", "whitelist"
    };

    /** 权限等级 4（Owner）。 */
    private static final String[] LEVEL_4 = {
            "jfr", "perf", "publish", "save-all", "save-off", "save-on", "stop"
    };

    private static final Map<String, Integer> VANILLA_LEVELS = buildVanillaLevels();
    private static final Map<String, Integer> PROBE_CACHE = new ConcurrentHashMap<>();

    private CommandPermissions() {}

    private static Map<String, Integer> buildVanillaLevels() {
        Map<String, Integer> map = new HashMap<>();
        put(map, LEVEL_0, LEVEL_ALL);
        put(map, LEVEL_2, LEVEL_GAMEMASTERS);
        put(map, LEVEL_3, LEVEL_ADMINS);
        put(map, LEVEL_4, LEVEL_OWNERS);
        // /seed：专用服务器需要等级 2（集成服务器在 resolve 里单独放行）。
        map.put("seed", LEVEL_GAMEMASTERS);
        return Map.copyOf(map);
    }

    private static void put(Map<String, Integer> map, String[] names, int level) {
        for (String name : names) {
            map.put(name, level);
        }
    }

    /** 原版表里的等级；未收录返回 {@link #UNKNOWN}。 */
    public static int vanillaLevel(String root) {
        Integer level = VANILLA_LEVELS.get(CommandPattern.normalize(root));
        return level == null ? UNKNOWN : level;
    }

    /** 是否是本模组收录的原版命令。 */
    public static boolean isVanilla(String root) {
        return vanillaLevel(root) != UNKNOWN;
    }

    /** 原版表里所有命令根名（文档/调试用）。 */
    public static Map<String, Integer> vanillaTable() {
        return VANILLA_LEVELS;
    }

    /**
     * 解析一条命令当前所需的权限等级。
     *
     * @param root         命令根名
     * @param source       命令源（用于 /seed 判定与命令树探测，可为 {@code null}）
     * @param probeEnabled 是否允许命令树探测
     * @param unknownLevel 未收录且探测失败的兜底等级
     */
    public static int resolve(String root, CommandSourceStack source, boolean probeEnabled, int unknownLevel) {
        String key = CommandPattern.normalize(root);
        if ("seed".equals(key)) {
            MinecraftServer server = source == null ? null : source.getServer();
            if (server != null && server.isSingleplayer()) {
                return LEVEL_ALL;
            }
            return LEVEL_GAMEMASTERS;
        }
        int vanilla = vanillaLevel(key);
        if (vanilla != UNKNOWN) {
            return vanilla;
        }
        // 探测的四条禁令（缺一条都可能重演 1.0.2 的递归卡死）：
        //   1) 配置关掉探测就不探；
        //   2) 不用 computeIfAbsent（映射函数重入 ConcurrentHashMap 会挂起），改成 get + putIfAbsent；
        //   3) 判定进行中（闸门内部）只有在能拿到“包装前谓词”时才探——否则 probe 会测到包装后的谓词；
        //   4) probe 内部再兜一层（见下）。
        if (probeEnabled && source != null && source.getServer() != null) {
            boolean safeToProbe = !ReentrancyGuard.isActive() || CommandGate.originalRequirement(key) != null;
            if (safeToProbe) {
                Integer cached = PROBE_CACHE.get(key);
                if (cached == null) {
                    int probed = probe(key, source);
                    PROBE_CACHE.putIfAbsent(key, probed);
                    cached = probed;
                }
                if (cached != UNKNOWN) {
                    return cached;
                }
            }
        }
        return unknownLevel;
    }

    /**
     * 用“伪造权限等级的命令源”测试命令树根节点的 requirement，取最小可用等级。
     *
     * <p>要求谓词本身可能访问执行者实体、维度或服务器类型，这种情况下会抛异常或对所有等级都返回
     * false，此时视为探测失败（返回 {@link #UNKNOWN}），由调用方回落到配置的兜底等级。
     */
    private static int probe(String root, CommandSourceStack source) {
        try {
            MinecraftServer server = source.getServer();
            if (server == null) {
                return UNKNOWN;
            }
            CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
            var node = dispatcher.getRoot().getChild(root);
            if (node == null) {
                return UNKNOWN;
            }
            // 必须测“包装前”的原始 requirement：节点当前的 requirement 可能已被 CommandGate
            // 换成 “原 requirement AND 本模组判定”，那样探测会递归回 allowRoot 直至卡死。
            java.util.function.Predicate<CommandSourceStack> requirement =
                    CommandGate.originalRequirement(root);
            if (requirement == null) {
                if (ReentrancyGuard.isActive()) {
                    // 正在闸门判定中、又拿不到原始谓词：绝不退回节点当前的（包装后）谓词，直接放弃探测
                    return UNKNOWN;
                }
                requirement = node.getRequirement();
            }
            for (int level = LEVEL_ALL; level <= LEVEL_OWNERS; level++) {
                CommandSourceStack probeSource = server.createCommandSourceStack()
                        .withPermission(level)
                        .withSuppressedOutput();
                if (requirement.test(probeSource)) {
                    return level;
                }
            }
        } catch (Throwable ignored) {
            // 要求谓词依赖真实执行者时探测无意义，回落到兜底等级。
        }
        return UNKNOWN;
    }

    /** 命令表变化（/reload、数据包重载）后清空探测缓存。 */
    public static void clearProbeCache() {
        PROBE_CACHE.clear();
    }
}
