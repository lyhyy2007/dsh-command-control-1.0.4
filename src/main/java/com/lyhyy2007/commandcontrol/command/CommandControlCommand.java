package com.lyhyy2007.commandcontrol.command;

import com.lyhyy2007.commandcontrol.CommandGate;
import com.lyhyy2007.commandcontrol.CommandGuard;
import com.lyhyy2007.commandcontrol.CommandPermissions;
import com.lyhyy2007.commandcontrol.CommandControl;
import com.lyhyy2007.commandcontrol.GuardPreset;
import com.lyhyy2007.commandcontrol.GuardState;
import com.lyhyy2007.commandcontrol.config.CommandControlConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * {@code /commandcontrol} 管理命令：给服主 / 联机主机 / 管理员在游戏内调控使用的入口。
 *
 * <p>需要权限等级 3（ADMINS）以上；单人开作弊或局域网主机通常是等级 4。
 * 所有修改都会写回 {@code config/command-control-common.toml} 并立刻刷新 {@link GuardState}，实时生效。
 *
 * <p>所有者：lyhyy2007　程序提供：deepseek
 *
 * <pre>
 * /commandcontrol status                          查看当前状态（含署名与版本）
 * /commandcontrol preset &lt;1-4|名称&gt;                切换启用组
 * /commandcontrol toggle &lt;true|false&gt;              总开关
 * /commandcontrol gamemodeguard &lt;true|false&gt;       是否拦住 F3+F4 / 游戏模式切换
 * /commandcontrol bypass &lt;0-5&gt;                    绕过所需权限等级（5=开作弊也不放行）
 * /commandcontrol restrict &lt;模式|all&gt; &lt;true|false&gt;  管辖哪些游戏模式
 * /commandcontrol allow add|remove|list ...       自定义启用列表
 * /commandcontrol deny  add|remove|list ...       自定义禁用列表
 * /commandcontrol whitelist add|remove|list       白名单玩家
 * /commandcontrol info                            原版命令权限等级表摘要
 * </pre>
 */
public final class CommandControlCommand {

    private CommandControlCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("commandcontrol")
                .requires(source -> source.hasPermission(Commands.LEVEL_ADMINS))
                .executes(context -> status(context.getSource()))
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("preset")
                        .executes(context -> status(context.getSource()))
                        .then(Commands.argument("value", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(GuardPreset.suggestions(), builder))
                                .executes(context -> setPreset(context.getSource(),
                                        StringArgumentType.getString(context, "value")))))
                .then(Commands.literal("toggle")
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(context -> setEnabled(context.getSource(),
                                        BoolArgumentType.getBool(context, "enabled")))))
                .then(Commands.literal("gamemodeguard")
                        .executes(context -> status(context.getSource()))
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(context -> setGameModeGuard(context.getSource(),
                                        BoolArgumentType.getBool(context, "enabled")))))
                .then(Commands.literal("gate")
                        .executes(context -> status(context.getSource()))
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(context -> setDispatcherGate(context.getSource(),
                                        BoolArgumentType.getBool(context, "enabled")))))
                .then(Commands.literal("bypass")
                        .executes(context -> status(context.getSource()))
                        .then(Commands.argument("level", IntegerArgumentType.integer(0, 5))
                                .executes(context -> setBypassLevel(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "level")))))
                .then(Commands.literal("restrict")
                        .executes(context -> status(context.getSource()))
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        new String[] {"survival", "creative", "adventure", "spectator", "all"}, builder))
                                .then(Commands.argument("enabled", BoolArgumentType.bool())
                                        .executes(context -> setModeRestriction(context.getSource(),
                                                StringArgumentType.getString(context, "mode"),
                                                BoolArgumentType.getBool(context, "enabled"))))))
                .then(listBranch("allow", CommandControlConfig.ENABLED_COMMANDS, "启用列表"))
                .then(listBranch("deny", CommandControlConfig.DISABLED_COMMANDS, "禁用列表"))
                .then(Commands.literal("whitelist")
                        .executes(context -> listWhitelist(context.getSource()))
                        .then(Commands.literal("list").executes(context -> listWhitelist(context.getSource())))
                        .then(Commands.literal("add")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                context.getSource().getServer().getPlayerNames(), builder))
                                        .executes(context -> addWhitelist(context.getSource(),
                                                StringArgumentType.getString(context, "player")))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("player", StringArgumentType.greedyString())
                                        .executes(context -> removeWhitelist(context.getSource(),
                                                StringArgumentType.getString(context, "player"))))))
                .then(Commands.literal("info").executes(context -> info(context.getSource())))
                .then(Commands.literal("diag").executes(context -> diagnostics(context.getSource())));

        dispatcher.register(root);
    }

    // ------------------------------------------------------------- 子命令实现

    private static int status(CommandSourceStack source) {
        GuardState state = GuardState.current();
        reply(source, "—— Command Control 状态（所有者 " + CommandControl.OWNER
                + "，程序提供 " + CommandControl.PROVIDER + "，v" + CommandControl.VERSION + "）——");
        reply(source, "总开关：" + (state.enabled() ? "开启" : "关闭")
                + "　启用组：" + state.preset().id() + "（" + state.preset().displayName() + "）");
        reply(source, "管辖模式：" + state.restrictedModeSummary()
                + "　模式切换保护（拦 F3+F4）：" + (state.protectGameModeSwitch() ? "开启" : "关闭"));
        reply(source, "绕过所需权限等级：" + state.bypassPermissionLevel() + "（5=谁都不放行，开作弊也受限）"
                + "　未知命令兜底等级：" + state.unknownCommandLevel());
        reply(source, "放行控制台/RCON：" + state.allowConsoleSources()
                + "　放行命令方块：" + state.allowCommandBlocks());
        reply(source, "配置结构版本：" + CommandControlConfig.CONFIG_VERSION.get()
                + "（当前 " + CommandControlConfig.CURRENT_CONFIG_VERSION + "，自动迁移）"
                + "　命令树闸门：已装 " + CommandGate.gatedRootCount() + " 个根命令"
                + "（拦截 " + CommandGate.blockedCount() + " 次）"
                + "　执行层拦截：" + CommandGuard.blockedCommandCount()
                + " 次　模式切换拦截：" + CommandGuard.blockedGameModeCount() + " 次");
        reply(source, "自定义启用列表（" + state.enabledPatterns().size() + "）：" + state.enabledPatterns());
        reply(source, "自定义禁用列表（" + state.disabledPatterns().size() + "）：" + state.disabledPatterns());
        reply(source, "白名单（" + state.whitelist().size() + "）：" + state.whitelist());
        return 1;
    }

    private static int setPreset(CommandSourceStack source, String value) {
        GuardPreset preset = GuardPreset.parse(value);
        if (preset == null) {
            return failure(source, "无法识别的启用组：" + value + "（可用：1=禁用指令，2=不禁用指令，或 DISABLED / NO_RESTRICTION）");
        }
        CommandControlConfig.PRESET.set(preset);
        CommandControlConfig.save();
        GuardState.refresh();
        return reply(source, "启用组已切换为 " + preset.id() + "（" + preset.displayName() + "）：" + preset.description());
    }

    private static int setEnabled(CommandSourceStack source, boolean enabled) {
        CommandControlConfig.ENABLED.set(enabled);
        CommandControlConfig.save();
        GuardState.refresh();
        return reply(source, "Command Control 总开关：" + (enabled ? "开启" : "关闭"));
    }

    private static int setGameModeGuard(CommandSourceStack source, boolean enabled) {
        CommandControlConfig.PROTECT_GAME_MODE_SWITCH.set(enabled);
        CommandControlConfig.save();
        GuardState.refresh();
        return reply(source, "游戏模式切换保护（F3+F4 / /gamemode）：" + (enabled ? "开启" : "关闭"));
    }

    /** 开/关第一层“命令树强制闸门”（改完需要 /reload 让它重新安装到新的命令表上）。 */
    private static int setDispatcherGate(CommandSourceStack source, boolean enabled) {
        CommandControlConfig.ENABLE_DISPATCHER_GATE.set(enabled);
        CommandControlConfig.save();
        GuardState.refresh();
        return reply(source, "命令树强制闸门（第一层）：" + (enabled ? "开启" : "关闭")
                + (enabled ? "　执行 /reload 让它装到命令表上（关闭则 /reload 后不再包装）" : "　执行 /reload 生效"));
    }

    private static int setBypassLevel(CommandSourceStack source, int level) {
        CommandControlConfig.BYPASS_PERMISSION_LEVEL.set(level);
        CommandControlConfig.save();
        GuardState.refresh();
        return reply(source, "绕过所需权限等级已设为 " + level
                + (level >= 5 ? "（谁都不放行：开作弊的玩家也受限）" : "（权限等级 >= " + level + " 的命令源放行）"));
    }

    private static int setModeRestriction(CommandSourceStack source, String mode, boolean enabled) {
        String key = mode.toLowerCase(java.util.Locale.ROOT);
        switch (key) {
            case "survival" -> CommandControlConfig.RESTRICT_SURVIVAL.set(enabled);
            case "creative" -> CommandControlConfig.RESTRICT_CREATIVE.set(enabled);
            case "adventure" -> CommandControlConfig.RESTRICT_ADVENTURE.set(enabled);
            case "spectator" -> CommandControlConfig.RESTRICT_SPECTATOR.set(enabled);
            case "all" -> {
                CommandControlConfig.RESTRICT_SURVIVAL.set(enabled);
                CommandControlConfig.RESTRICT_CREATIVE.set(enabled);
                CommandControlConfig.RESTRICT_ADVENTURE.set(enabled);
                CommandControlConfig.RESTRICT_SPECTATOR.set(enabled);
            }
            default -> {
                return failure(source, "未知游戏模式：" + mode + "（可用：survival / creative / adventure / spectator / all）");
            }
        }
        CommandControlConfig.save();
        GuardState.refresh();
        return reply(source, "管辖模式 " + key + "：" + (enabled ? "已纳入管辖" : "已移出管辖")
                + "　当前管辖：" + GuardState.current().restrictedModeSummary());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> listBranch(
            String name, ModConfigSpec.ConfigValue<List<? extends String>> value, String label) {
        return Commands.literal(name)
                .executes(context -> listEntries(context.getSource(), value, label))
                .then(Commands.literal("list").executes(context -> listEntries(context.getSource(), value, label)))
                .then(Commands.literal("add")
                        .then(Commands.argument("pattern", StringArgumentType.greedyString())
                                .executes(context -> addEntry(context.getSource(), value, label,
                                        StringArgumentType.getString(context, "pattern")))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("pattern", StringArgumentType.greedyString())
                                .executes(context -> removeEntry(context.getSource(), value, label,
                                        StringArgumentType.getString(context, "pattern")))));
    }

    private static int listEntries(CommandSourceStack source,
                                   ModConfigSpec.ConfigValue<List<? extends String>> value,
                                   String label) {
        List<? extends String> entries = value.get();
        if (entries.isEmpty()) {
            return reply(source, label + "为空。");
        }
        reply(source, label + "（" + entries.size() + "）：");
        for (String entry : entries) {
            reply(source, "  - " + entry);
        }
        return entries.size();
    }

    private static int addEntry(CommandSourceStack source,
                                ModConfigSpec.ConfigValue<List<? extends String>> value,
                                String label, String entry) {
        String pattern = entry == null ? "" : entry.trim();
        if (pattern.isEmpty()) {
            return failure(source, "模式不能为空。");
        }
        List<String> list = new ArrayList<>(value.get());
        if (list.contains(pattern)) {
            return reply(source, label + "里已经有：" + pattern);
        }
        list.add(pattern);
        value.set(list);
        CommandControlConfig.save();
        GuardState.refresh();
        return reply(source, label + "已添加：" + pattern + "（共 " + list.size() + " 条）");
    }

    private static int removeEntry(CommandSourceStack source,
                                   ModConfigSpec.ConfigValue<List<? extends String>> value,
                                   String label, String entry) {
        String pattern = entry == null ? "" : entry.trim();
        List<String> list = new ArrayList<>(value.get());
        boolean removed = list.removeIf(existing -> existing.equalsIgnoreCase(pattern));
        if (!removed) {
            return failure(source, label + "里没有：" + pattern);
        }
        value.set(list);
        CommandControlConfig.save();
        GuardState.refresh();
        return reply(source, label + "已移除：" + pattern + "（剩 " + list.size() + " 条）");
    }

    private static int listWhitelist(CommandSourceStack source) {
        return listEntries(source, CommandControlConfig.WHITELIST_PLAYERS, "白名单");
    }

    private static int addWhitelist(CommandSourceStack source, String name) {
        ServerPlayer player = source.getServer().getPlayerList().getPlayerByName(name);
        String entry = player != null ? player.getGameProfile().getName() : name;
        int result = addEntry(source, CommandControlConfig.WHITELIST_PLAYERS, "白名单", entry);
        if (player != null) {
            reply(source, "已按在线玩家 " + player.getGameProfile().getName()
                    + "（" + player.getStringUUID() + "）加入白名单");
        }
        return result;
    }

    private static int removeWhitelist(CommandSourceStack source, String name) {
        return removeEntry(source, CommandControlConfig.WHITELIST_PLAYERS, "白名单", name);
    }

    private static int info(CommandSourceStack source) {
        GuardState state = GuardState.current();
        reply(source, "—— Command Control 启用组对照（1.0.4 起只有两个）——");
        for (GuardPreset preset : GuardPreset.values()) {
            reply(source, preset.id() + ". " + preset.displayName() + "：" + preset.description()
                    + "　【最高放行等级 " + preset.highestAllowedLevel() + "】");
        }
        Map<String, Integer> table = CommandPermissions.vanillaTable();
        int level0 = 0;
        int level2 = 0;
        int level3 = 0;
        int level4 = 0;
        for (int level : table.values()) {
            switch (level) {
                case CommandPermissions.LEVEL_ALL -> level0++;
                case CommandPermissions.LEVEL_GAMEMASTERS -> level2++;
                case CommandPermissions.LEVEL_ADMINS -> level3++;
                case CommandPermissions.LEVEL_OWNERS -> level4++;
                default -> {
                }
            }
        }
        reply(source, "原版 1.21.1 命令库：等级 0 共 " + level0 + " 条，等级 2 共 " + level2
                + " 条，等级 3 共 " + level3 + " 条，等级 4 共 " + level4 + " 条（等级 1 已无命令）。");
        reply(source, "当前启用组 " + state.preset().id() + "，最高放行等级：" + state.preset().highestAllowedLevel());
        return 1;
    }

    // ------------------------------------------------------------------ 工具

    /**
     * {@code /commandcontrol diag}：把“三层拦截到底装没装、当前判定结果是什么”摊开来讲。
     *
     * <p>这里的判定走的是与闸门/执行层<b>同一套</b> {@link GuardState#decide}，
     * 所以它输出的结论就是实际会发生的行为（不会真的执行命令）。
     */
    private static int diagnostics(CommandSourceStack source) {
        GuardState state = GuardState.current();
        reply(source, "—— Command Control 诊断（v" + CommandControl.VERSION + "，所有者 "
                + CommandControl.OWNER + "，程序提供 " + CommandControl.PROVIDER + "）——");
        reply(source, "配置文件：config/" + "command-control-common.toml"
                + "　configVersion=" + CommandControlConfig.CONFIG_VERSION.get()
                + "（当前 " + CommandControlConfig.CURRENT_CONFIG_VERSION + "）");
        reply(source, "快照状态：ready=" + state.ready()
                + "　总开关=" + (state.enabled() ? "开" : "关")
                + "　启用组=" + state.preset().id() + "." + state.preset().displayName()
                + "　管辖模式=" + state.restrictedModeSummary()
                + "　绕过等级=" + state.bypassPermissionLevel());
        reply(source, "① 命令树强制闸门：开关=" + (state.enableDispatcherGate() ? "开" : "关")
                + "，已包装根命令 " + CommandGate.gatedRootCount()
                + " 个，安装 " + CommandGate.installCount() + " 次，拦截 " + CommandGate.blockedCount() + " 次"
                + "（give=" + CommandGate.isGated("give") + "，tp=" + CommandGate.isGated("tp") + "）");
        reply(source, "② CommandEvent 执行前取消：已注册，拦截 " + CommandGuard.blockedCommandCount() + " 次");
        reply(source, "③ 游戏模式切换拦截：保护开关=" + (state.protectGameModeSwitch() ? "开" : "关")
                + "，拦截 " + CommandGuard.blockedGameModeCount() + " 次");
        GuardState.SourceFacts facts = CommandGate.factsOf(source);
        reply(source, "当前命令源：" + CommandGate.describeSource(source)
                + "（玩家=" + facts.player() + "，模式受管辖=" + facts.gameModeRestricted()
                + "，白名单=" + facts.whitelisted() + "，达到绕过等级=" + facts.bypassed() + "）");
        for (String root : new String[] {"give", "gamemode", "tp", "weather", "time", "help", "list"}) {
            int level = CommandPermissions.resolve(root, source, state.useDispatcherProbe(), state.unknownCommandLevel());
            GuardState.Verdict verdict = state.decide(java.util.List.of(root), level, facts);
            reply(source, "　/" + root + "（等级 " + level + "）→ "
                    + (verdict.allowed() ? "放行" : "拦截") + "：" + verdict.reason());
        }
        if (state.blocksNothing()) {
            reply(source, "⚠ 当前配置不会拦截任何命令——用 /commandcontrol preset 1 让它生效。");
        }
        return 1;
    }

    private static int reply(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int failure(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        CommandControl.LOGGER.debug("命令执行失败：{}", message);
        return 0;
    }
}
