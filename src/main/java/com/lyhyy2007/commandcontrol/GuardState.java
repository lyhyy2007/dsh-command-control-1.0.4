package com.lyhyy2007.commandcontrol;

import com.lyhyy2007.commandcontrol.config.CommandControlConfig;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * 配置的运行时快照（不可变）+ 全部判定逻辑（纯函数，可离线测试）。
 *
 * <p>1.0.2 重构要点：
 * <ul>
 *   <li><b>判定与 Minecraft 解耦</b>：判定只依赖 {@link SourceFacts}（玩家/模式/白名单/权限等级/来源类型）
 *       与命令权限等级，因此命令树“强制闸门”和 {@code CommandEvent} 第二层能共用同一套结论，
 *       也能在没有游戏环境时被 {@code SelfTest} 完整覆盖。</li>
 *   <li><b>两级判定</b>：{@link #decideRoot} 用于解析期闸门（只知道命令名），
 *       {@link #decide} 用于执行期（拿得到完整命令行，能做参数级过滤）。</li>
 *   <li><b>自愈</b>：{@link #current()} 发现快照还是兜底值、但配置其实已经加载时，会自动重建一次，
 *       不依赖配置事件是否送达。</li>
 * </ul>
 */
public final class GuardState {

    /** 本模组自己的管理命令根名：任何启用组下都放行，否则严格组会让服主无法在游戏内调控。 */
    public static final String MANAGE_COMMAND = "commandcontrol";

    /** 判定结论。 */
    public record Verdict(boolean allowed, String reason) {
        public static Verdict allow(String reason) {
            return new Verdict(true, reason);
        }

        public static Verdict deny(String reason) {
            return new Verdict(false, reason);
        }
    }

    /**
     * 判定所需的“命令源事实”，全部是原始类型，方便离线测试。
     *
     * @param player              是否是玩家发起的命令
     * @param gameModeRestricted  玩家当前游戏模式是否受管辖（非玩家来源无意义）
     * @param whitelisted         玩家是否在白名单里
     * @param bypassed            命令源权限等级是否已达到 bypassPermissionLevel（默认 5 → 永远 false）
     * @param consoleLike         非玩家来源时：true=控制台/RCON，false=命令方块/矿车
     */
    public record SourceFacts(boolean player,
                              boolean gameModeRestricted,
                              boolean whitelisted,
                              boolean bypassed,
                              boolean consoleLike) {
    }

    private static final GuardState FALLBACK = new GuardState(
            false,
            GuardPreset.DISABLED,
            true, true, true, true,
            true,
            true,
            false,
            true,
            true,
            5,
            2,
            "该命令已被 Command Control 禁用：{command}（当前启用组：{preset}）",
            "游戏模式切换已被 Command Control 禁用",
            Set.of(),
            List.of(),
            List.of());

    private static volatile GuardState current = FALLBACK;

    private final boolean enabled;
    private final GuardPreset preset;
    private final boolean restrictSurvival;
    private final boolean restrictCreative;
    private final boolean restrictAdventure;
    private final boolean restrictSpectator;
    private final boolean protectGameModeSwitch;
    private final boolean allowConsoleSources;
    private final boolean allowCommandBlocks;
    private final boolean useDispatcherProbe;
    private final boolean enableDispatcherGate;
    private final int bypassPermissionLevel;
    private final int unknownCommandLevel;
    private final String deniedMessage;
    private final String gameModeDeniedMessage;
    private final Set<String> whitelist;
    private final List<CommandPattern> disabledPatterns;
    private final List<CommandPattern> enabledPatterns;
    /** true = 从配置构建；false = 兜底快照（配置尚未加载）。 */
    private final boolean ready;

    private GuardState(boolean enabled,
                       GuardPreset preset,
                       boolean restrictSurvival,
                       boolean restrictCreative,
                       boolean restrictAdventure,
                       boolean restrictSpectator,
                       boolean protectGameModeSwitch,
                       boolean allowConsoleSources,
                       boolean allowCommandBlocks,
                       boolean useDispatcherProbe,
                       boolean enableDispatcherGate,
                       int bypassPermissionLevel,
                       int unknownCommandLevel,
                       String deniedMessage,
                       String gameModeDeniedMessage,
                       Set<String> whitelist,
                       List<CommandPattern> disabledPatterns,
                       List<CommandPattern> enabledPatterns) {
        this(enabled, preset, restrictSurvival, restrictCreative, restrictAdventure, restrictSpectator,
                protectGameModeSwitch, allowConsoleSources, allowCommandBlocks, useDispatcherProbe,
                enableDispatcherGate, bypassPermissionLevel, unknownCommandLevel, deniedMessage, gameModeDeniedMessage,
                whitelist, disabledPatterns, enabledPatterns, true);
    }

    private GuardState(boolean enabled,
                       GuardPreset preset,
                       boolean restrictSurvival,
                       boolean restrictCreative,
                       boolean restrictAdventure,
                       boolean restrictSpectator,
                       boolean protectGameModeSwitch,
                       boolean allowConsoleSources,
                       boolean allowCommandBlocks,
                       boolean useDispatcherProbe,
                       boolean enableDispatcherGate,
                       int bypassPermissionLevel,
                       int unknownCommandLevel,
                       String deniedMessage,
                       String gameModeDeniedMessage,
                       Set<String> whitelist,
                       List<CommandPattern> disabledPatterns,
                       List<CommandPattern> enabledPatterns,
                       boolean ready) {
        this.enabled = enabled;
        this.preset = preset;
        this.restrictSurvival = restrictSurvival;
        this.restrictCreative = restrictCreative;
        this.restrictAdventure = restrictAdventure;
        this.restrictSpectator = restrictSpectator;
        this.protectGameModeSwitch = protectGameModeSwitch;
        this.allowConsoleSources = allowConsoleSources;
        this.allowCommandBlocks = allowCommandBlocks;
        this.useDispatcherProbe = useDispatcherProbe;
        this.enableDispatcherGate = enableDispatcherGate;
        this.bypassPermissionLevel = bypassPermissionLevel;
        this.unknownCommandLevel = unknownCommandLevel;
        this.deniedMessage = deniedMessage;
        this.gameModeDeniedMessage = gameModeDeniedMessage;
        this.whitelist = whitelist;
        this.disabledPatterns = disabledPatterns;
        this.enabledPatterns = enabledPatterns;
        this.ready = ready;
    }

    /**
     * 当前快照。若当前还是兜底快照、而配置其实已经加载好（例如配置事件被漏掉），
     * 就地自愈重建一次，保证“肯定不会因为时序问题而静默失效”。
     */
    public static GuardState current() {
        GuardState state = current;
        if (!state.ready && CommandControlConfig.SPEC.isLoaded()) {
            refresh();
            state = current;
        }
        return state;
    }

    /** 兜底快照（不拦任何命令）。 */
    public static GuardState fallback() {
        return FALLBACK;
    }

    /**
     * 按显式参数构造快照（不读配置文件）。
     *
     * <p>用于纯逻辑自检与诊断：四个游戏模式固定为“全部管辖”，消息用占位模板。
     * 生产路径请始终使用 {@link #current()} / {@link #refresh()}。
     */
    public static GuardState of(boolean enabled,
                                GuardPreset preset,
                                boolean protectGameModeSwitch,
                                boolean allowConsoleSources,
                                boolean allowCommandBlocks,
                                boolean enableDispatcherGate,
                                int bypassPermissionLevel,
                                int unknownCommandLevel,
                                Set<String> whitelist,
                                List<CommandPattern> disabledPatterns,
                                List<CommandPattern> enabledPatterns) {
        return new GuardState(enabled, preset, true, true, true, true, protectGameModeSwitch,
                allowConsoleSources, allowCommandBlocks, true, enableDispatcherGate, bypassPermissionLevel,
                unknownCommandLevel,
                "该命令已被 Command Control 禁用：{command}（当前启用组：{preset}）",
                "游戏模式切换已被 Command Control 禁用",
                whitelist, disabledPatterns, enabledPatterns, true);
    }

    /** 重新从配置构建快照。 */
    public static void refresh() {
        try {
            current = new GuardState(
                    CommandControlConfig.ENABLED.get(),
                    CommandControlConfig.PRESET.get(),
                    CommandControlConfig.RESTRICT_SURVIVAL.get(),
                    CommandControlConfig.RESTRICT_CREATIVE.get(),
                    CommandControlConfig.RESTRICT_ADVENTURE.get(),
                    CommandControlConfig.RESTRICT_SPECTATOR.get(),
                    CommandControlConfig.PROTECT_GAME_MODE_SWITCH.get(),
                    CommandControlConfig.ALLOW_CONSOLE_SOURCES.get(),
                    CommandControlConfig.ALLOW_COMMAND_BLOCKS.get(),
                    CommandControlConfig.USE_DISPATCHER_PROBE.get(),
                    CommandControlConfig.ENABLE_DISPATCHER_GATE.get(),
                    CommandControlConfig.BYPASS_PERMISSION_LEVEL.get(),
                    CommandControlConfig.UNKNOWN_COMMAND_LEVEL.get(),
                    CommandControlConfig.DENIED_MESSAGE.get(),
                    CommandControlConfig.GAME_MODE_DENIED_MESSAGE.get(),
                    buildWhitelist(CommandControlConfig.WHITELIST_PLAYERS.get()),
                    buildPatterns(CommandControlConfig.DISABLED_COMMANDS.get()),
                    buildPatterns(CommandControlConfig.ENABLED_COMMANDS.get()));
        } catch (IllegalStateException notLoadedYet) {
            current = FALLBACK;
        }
    }

    private static Set<String> buildWhitelist(List<? extends String> raw) {
        Set<String> set = new HashSet<>();
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            set.add(entry.trim().toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(set);
    }

    private static List<CommandPattern> buildPatterns(List<? extends String> raw) {
        List<CommandPattern> patterns = new ArrayList<>(raw.size());
        for (String entry : raw) {
            CommandPattern pattern = CommandPattern.compile(entry);
            if (pattern != null) {
                patterns.add(pattern);
            }
        }
        return List.copyOf(patterns);
    }

    // ================================================================= 判定

    /**
     * 解析期判定（命令树闸门用）：只知道命令根名，不知道参数。
     *
     * @param root         命令根名（小写、已去命名空间）
     * @param commandLevel 该命令的权限等级（{@link CommandPermissions#resolve} 的结果）
     */
    public Verdict decideRoot(String root, int commandLevel, SourceFacts facts) {
        if (isManageCommand(root)) {
            return Verdict.allow("本模组管理命令");
        }
        Verdict sourceVerdict = checkSource(facts);
        if (sourceVerdict != null) {
            return sourceVerdict;
        }
        // 整条命令被禁用列表点名 → 解析期就截断
        if (rootExactlyDisabled(root)) {
            return Verdict.deny("自定义禁用列表（整条命令）");
        }
        // 启用列表里出现该命令 → 解析期先放行，参数级细节交给执行期精确判定
        if (rootMaybeExplicitlyAllowed(root)) {
            return Verdict.allow("自定义启用列表（参数级判定留到执行期）");
        }
        if (preset.allows(root, commandLevel)) {
            return Verdict.allow("启用组 " + preset.id());
        }
        return Verdict.deny("启用组 " + preset.id() + "（" + preset.displayName() + "）不允许 /" + root
                + "（其权限等级为 " + commandLevel + "）");
    }

    /**
     * 执行期判定（{@code CommandEvent} 用）：拿得到完整命令行，可以做参数级过滤。
     */
    public Verdict decide(List<String> tokens, int commandLevel, SourceFacts facts) {
        String root = tokens.isEmpty() ? "" : CommandPattern.normalize(tokens.get(0));
        if (isManageCommand(root)) {
            return Verdict.allow("本模组管理命令");
        }
        Verdict sourceVerdict = checkSource(facts);
        if (sourceVerdict != null) {
            return sourceVerdict;
        }
        if (matchesEnabled(tokens)) {
            return Verdict.allow("自定义启用列表");
        }
        if (matchesDisabled(tokens)) {
            return Verdict.deny("自定义禁用列表");
        }
        if (preset.allows(root, commandLevel)) {
            return Verdict.allow("启用组 " + preset.id());
        }
        return Verdict.deny("启用组 " + preset.id() + "（" + preset.displayName() + "）不允许 /" + root
                + "（其权限等级为 " + commandLevel + "）");
    }

    /**
     * 与命令名无关的公共前置判定。
     *
     * @return 非 null 表示已经作出结论；null 表示需要继续按命令名判定
     */
    private Verdict checkSource(SourceFacts facts) {
        if (!enabled) {
            return Verdict.allow("总开关关闭");
        }
        if (!facts.player()) {
            if (facts.consoleLike()) {
                return allowConsoleSources
                        ? Verdict.allow("控制台/RCON 放行")
                        : Verdict.deny("控制台/RCON 已被配置禁止");
            }
            return allowCommandBlocks
                    ? Verdict.allow("命令方块放行")
                    : Verdict.deny("命令方块已被配置禁止");
        }
        if (facts.whitelisted()) {
            return Verdict.allow("白名单玩家");
        }
        if (!facts.gameModeRestricted()) {
            return Verdict.allow("该游戏模式不在管辖范围");
        }
        if (facts.bypassed()) {
            return Verdict.allow("权限等级已达到 bypassPermissionLevel " + bypassPermissionLevel);
        }
        return null;
    }

    /** 是否是本模组的管理命令（始终放行，靠它自身的权限等级把关）。 */
    public boolean isManageCommand(String root) {
        return MANAGE_COMMAND.equals(CommandPattern.normalize(root));
    }

    /** 该根命令是否被“单命令”禁用模式整体点名。 */
    public boolean rootExactlyDisabled(String root) {
        String normalized = CommandPattern.normalize(root);
        for (CommandPattern pattern : disabledPatterns) {
            if (pattern.targetsOnly(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** 启用列表里是否有以该根命令开头的模式（参数级判定留给执行期）。 */
    public boolean rootMaybeExplicitlyAllowed(String root) {
        String normalized = CommandPattern.normalize(root);
        for (CommandPattern pattern : enabledPatterns) {
            if (pattern.targetsRoot(normalized)) {
                return true;
            }
        }
        return false;
    }

    // ================================================================= 查询

    public boolean enabled() {
        return enabled;
    }

    public boolean ready() {
        return ready;
    }

    public GuardPreset preset() {
        return preset;
    }

    public boolean protectGameModeSwitch() {
        return protectGameModeSwitch;
    }

    public boolean allowConsoleSources() {
        return allowConsoleSources;
    }

    public boolean allowCommandBlocks() {
        return allowCommandBlocks;
    }

    public boolean useDispatcherProbe() {
        return useDispatcherProbe;
    }

    /** 是否启用命令树强制闸门（第一层）。 */
    public boolean enableDispatcherGate() {
        return enableDispatcherGate;
    }

    /**
     * 当前是否应该（且允许）在命令树上装闸门。
     *
     * <p>三个条件缺一不可：总开关开、闸门开关开、预设为「禁用指令」。
     * 这样「不禁用指令」模式下命令树<b>完全不动</b>，{@code /tp}、{@code /time} 之类不会被隐藏。
     */
    public boolean shouldGateCommandTree() {
        return enabled && enableDispatcherGate && preset.needsCommandTreeGate();
    }

    public int bypassPermissionLevel() {
        return bypassPermissionLevel;
    }

    public int unknownCommandLevel() {
        return unknownCommandLevel;
    }

    public String deniedMessage() {
        return deniedMessage;
    }

    public String gameModeDeniedMessage() {
        return gameModeDeniedMessage;
    }

    public Set<String> whitelist() {
        return whitelist;
    }

    public List<CommandPattern> disabledPatterns() {
        return disabledPatterns;
    }

    public List<CommandPattern> enabledPatterns() {
        return enabledPatterns;
    }

    /**
     * 该游戏模式是否受本模组管辖。四个开关默认全开，
     * 所以生存 / 创造 / 冒险 / 旁观都会被管（不再局限于冒险模式）。
     */
    public boolean isRestrictedGameMode(GameType mode) {
        if (mode == null) {
            return true;
        }
        return switch (mode) {
            case SURVIVAL -> restrictSurvival;
            case CREATIVE -> restrictCreative;
            case ADVENTURE -> restrictAdventure;
            case SPECTATOR -> restrictSpectator;
        };
    }

    /**
     * 按名字判断是否受管辖（命令与纯逻辑自检用）。
     *
     * <p>单独开一个字符串入口是为了让 {@code SelfTest} 能在没有游戏环境的情况下验证这套矩阵：
     * {@code GameType} 的静态初始化会去碰聊天组件与 codec，脱离游戏直接加载它会失败。
     * 未知名字按“受管辖”处理，避免因为拼写问题漏管。
     */
    public boolean isRestrictedGameModeName(String modeName) {
        if (modeName == null) {
            return true;
        }
        return switch (modeName.trim().toLowerCase(Locale.ROOT)) {
            case "survival" -> restrictSurvival;
            case "creative" -> restrictCreative;
            case "adventure" -> restrictAdventure;
            case "spectator" -> restrictSpectator;
            default -> true;
        };
    }

    /** 受管辖的模式名（状态输出用）。 */
    public String restrictedModeSummary() {
        List<String> names = new ArrayList<>(4);
        if (restrictSurvival) {
            names.add("生存");
        }
        if (restrictCreative) {
            names.add("创造");
        }
        if (restrictAdventure) {
            names.add("冒险");
        }
        if (restrictSpectator) {
            names.add("旁观");
        }
        return names.isEmpty() ? "（无）" : String.join(" / ", names);
    }

    /**
     * 当前配置是否“什么都拦不住”。启动时若为 true 就打 WARN，
     * 避免再出现“装了但看起来没生效”却没有任何提示的情况。
     */
    public boolean blocksNothing() {
        return !enabled || (preset == GuardPreset.NO_RESTRICTION && disabledPatterns.isEmpty());
    }

    /** 命中自定义启用列表。 */
    public boolean matchesEnabled(List<String> tokens) {
        return matches(enabledPatterns, tokens);
    }

    /** 命中自定义禁用列表。 */
    public boolean matchesDisabled(List<String> tokens) {
        return matches(disabledPatterns, tokens);
    }

    private static boolean matches(List<CommandPattern> patterns, List<String> tokens) {
        for (CommandPattern pattern : patterns) {
            if (pattern.matches(tokens)) {
                return true;
            }
        }
        return false;
    }

    /** 玩家是否在白名单里（用户名或 UUID，忽略大小写）。 */
    public boolean isWhitelisted(ServerPlayer player) {
        if (whitelist.isEmpty()) {
            return false;
        }
        String name = player.getGameProfile().getName();
        if (name != null && whitelist.contains(name.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return whitelist.contains(player.getStringUUID().toLowerCase(Locale.ROOT));
    }

    /** 按模板生成拦截提示语。 */
    public String deniedMessageFor(List<String> tokens) {
        String command = "/" + String.join(" ", tokens);
        String root = tokens.isEmpty() ? "" : CommandPattern.normalize(tokens.get(0));
        return deniedMessage
                .replace("{command}", command)
                .replace("{root}", root)
                .replace("{preset}", preset.id() + "." + preset.displayName());
    }
}
