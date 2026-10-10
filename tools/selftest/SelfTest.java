import com.lyhyy2007.commandcontrol.CommandPattern;
import com.lyhyy2007.commandcontrol.CommandPermissions;
import com.lyhyy2007.commandcontrol.CommandTreeGate;
import com.lyhyy2007.commandcontrol.GuardPreset;
import com.lyhyy2007.commandcontrol.GuardState;
import com.lyhyy2007.commandcontrol.ReentrancyGuard;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.lyhyy2007.commandcontrol.config.CommandControlConfig;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.WritingMode;
import com.electronwill.nightconfig.toml.TomlFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.neoforged.fml.config.IConfigSpec;

/**
 * Command Control 纯逻辑自检（不需要启动 Minecraft）。
 *
 * <p>覆盖：命令模式匹配语义、四个启用组的放行规则、原版权限等级表、
 * 1.0.1 的管辖模式矩阵与管理命令放行规则。
 * 用法（Windows）：
 * <pre>
 *   javac -encoding UTF-8 -cp &lt;类路径&gt; -d out src/main/java/**&#47;*.java tools/selftest/SelfTest.java
 *   java -cp "out;&lt;类路径&gt;" SelfTest
 * </pre>
 */
public final class SelfTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        // ---------------------------------------------------------- 模式匹配
        patternMatches("give", "give @a minecraft:diamond", true);
        patternMatches("give", "give", true);
        patternMatches("give", "giver @a stone", false);
        patternMatches("/give", "give @a stone", true);
        patternMatches("give @a diamond", "give @a diamond 3", true);
        patternMatches("give @a diamond", "give @a stone", false);
        patternMatches("tp * 0 0", "tp @p 0 0", true);
        patternMatches("tp * 0 0", "tp @p 1 0", false);
        patternMatches("tp * 0 0", "tp @p 0", false);
        patternMatches("say hello **", "say hello", true);
        patternMatches("say hello **", "say hello world 123", true);
        patternMatches("say hello **", "say bye", false);
        patternMatches("minecraft:give", "give @a stone", true);
        patternMatches("ftbteams party create", "ftbteams party create my_team", true);
        patternMatches("weather", "weather rain", true);
        patternMatches("time", "time set day", true);

        // ---------------------------------------------------------- 启用组
        allows(GuardPreset.DISABLED, "help", 0, true);
        allows(GuardPreset.DISABLED, "list", 0, true);
        allows(GuardPreset.DISABLED, "give", 2, false);
        allows(GuardPreset.DISABLED, "tp", 2, false);
        allows(GuardPreset.DISABLED, "time", 2, false);
        allows(GuardPreset.DISABLED, "stop", 4, false);

        allows(GuardPreset.NO_RESTRICTION, "stop", 4, true);
        allows(GuardPreset.NO_RESTRICTION, "give", 2, true);
        allows(GuardPreset.NO_RESTRICTION, "tp", 2, true);
        allows(GuardPreset.NO_RESTRICTION, "time", 2, true);
        allows(GuardPreset.NO_RESTRICTION, "whatever:modded", 2, true);

        // 1.0.4：预设只剩两个，默认“禁用指令”
        check("预设只有两个", 2, GuardPreset.values().length);
        check("默认预设 = DISABLED（禁用指令）", GuardPreset.DISABLED, GuardPreset.byId(1));
        check("byId 越界回落到 DISABLED", GuardPreset.DISABLED, GuardPreset.byId(4));
        check("旧预设 TELEPORT_ONLY 已删除（解析失败）", null, GuardPreset.parse("TELEPORT_ONLY"));
        check("只有禁用指令需要命令树闸门", true, GuardPreset.DISABLED.needsCommandTreeGate());
        check("不禁用指令不装闸门", false, GuardPreset.NO_RESTRICTION.needsCommandTreeGate());

        // ---------------------------------------------------------- 等级表
        level("help", 0);
        level("me", 0);
        level("msg", 0);
        level("trigger", 0);
        level("give", 2);
        level("tp", 2);
        level("teleport", 2);
        level("spreadplayers", 2);
        level("weather", 2);
        level("time", 2);
        level("execute", 2);
        level("gamemode", 2);
        level("seed", 2);
        level("op", 3);
        level("kick", 3);
        level("whitelist", 3);
        level("stop", 4);
        level("save-all", 4);
        level("publish", 4);
        level("CommandControl", CommandPermissions.UNKNOWN);

        // resolve：source 为 null 时不探测，未知命令走兜底值
        resolve("give", 2);
        resolve("stop", 4);
        resolve("some_mod:some_command", 2);
        resolve("some_mod:some_command", 0, 0);

        // ------------------------------------------------ 1.0.1 新增：管辖范围与默认值
        GuardState fallback = GuardState.fallback();
        check("兜底状态不拦截任何命令", false, fallback.enabled());
        check("兜底默认组 = DISABLED（组1，装上即生效）", GuardPreset.DISABLED, fallback.preset());
        check("兜底 bypassPermissionLevel = 5（开作弊也不放行）", 5, fallback.bypassPermissionLevel());
        check("兜底模式切换保护 = 开（拦 F3+F4）", true, fallback.protectGameModeSwitch());
        check("兜底管辖生存模式", true, fallback.isRestrictedGameModeName("survival"));
        check("兜底管辖创造模式", true, fallback.isRestrictedGameModeName("creative"));
        check("兜底管辖冒险模式", true, fallback.isRestrictedGameModeName("adventure"));
        check("兜底管辖旁观模式", true, fallback.isRestrictedGameModeName("spectator"));
        check("未知模式名按受管辖处理", true, fallback.isRestrictedGameModeName("unknown_mode"));
        check("管辖模式摘要含四种模式", "生存 / 创造 / 冒险 / 旁观", fallback.restrictedModeSummary());
        check("管理命令始终放行：/commandcontrol", true, fallback.isManageCommand("commandcontrol"));
        check("管理命令始终放行：/command_control:commandcontrol", true,
                fallback.isManageCommand("command_control:commandcontrol"));
        check("普通命令不是管理命令", false, fallback.isManageCommand("give"));
        String denied = fallback.deniedMessageFor(java.util.List.of("give", "@a", "stone"));
        check("提示语替换 {command}", true, denied.contains("/give @a stone"));
        check("提示语替换 {preset}", true, denied.contains("1."));
        check("组 1 拦截 /gamemode（等级 2）", false, GuardPreset.DISABLED.allows("gamemode", 2));
        check("组 1 放行 /help（等级 0）", true, GuardPreset.DISABLED.allows("help", 0));

        // ------------------------------- 1.0.2：真实默认值（从生成的 TOML 断言）
        CommentedFileConfig defaults = writeDefaultConfig();
        check("默认 general.enabled = true", Boolean.TRUE, bool(defaults, "general.enabled"));
        check("默认 general.preset = DISABLED（组1）", "DISABLED", str(defaults, "general.preset"));
        check("默认 general.restrictSurvival = true", Boolean.TRUE, bool(defaults, "general.restrictSurvival"));
        check("默认 general.restrictCreative = true", Boolean.TRUE, bool(defaults, "general.restrictCreative"));
        check("默认 general.restrictAdventure = true", Boolean.TRUE, bool(defaults, "general.restrictAdventure"));
        check("默认 general.restrictSpectator = true", Boolean.TRUE, bool(defaults, "general.restrictSpectator"));
        check("默认 general.protectGameModeSwitch = true", Boolean.TRUE,
                bool(defaults, "general.protectGameModeSwitch"));
        check("默认 permissions.bypassPermissionLevel = 5", 5, num(defaults, "permissions.bypassPermissionLevel"));
        check("默认 permissions.allowConsoleSources = true", Boolean.TRUE,
                bool(defaults, "permissions.allowConsoleSources"));
        check("默认 permissions.allowCommandBlocks = false", Boolean.FALSE,
                bool(defaults, "permissions.allowCommandBlocks"));
        check("默认 permissions.unknownCommandLevel = 2", 2, num(defaults, "permissions.unknownCommandLevel"));
        check("默认 lists.disabledCommands 为空", 0,
                ((java.util.List<?>) raw(defaults, "lists.disabledCommands")).size());
        check("默认提示语含 {preset} 占位符", true, str(defaults, "messages.deniedMessage").contains("{preset}"));
        check("初始 configVersion = 0（触发一次性迁移）", 0, num(defaults, "general.configVersion"));
        check("默认开启命令树强制闸门", Boolean.TRUE, bool(defaults, "permissions.enableDispatcherGate"));

        // ------------------------------- 1.0.3：重入保护（1.0.2 卡死事故的回归测试）
        check("初始不在判定中", false, ReentrancyGuard.isActive());
        String guarded = ReentrancyGuard.call(() -> {
            check("判定中标记生效", true, ReentrancyGuard.isActive());
            check("判定中深度 = 1", 1, ReentrancyGuard.depth());
            // 再入必须走 fallback，绝不能继续递归（这正是 1.0.2 卡死的地方）
            String nested = ReentrancyGuard.call(() -> "递归了！", () -> "已拦截再入");
            check("再入时走 fallback（不递归）", "已拦截再入", nested);
            return "ok";
        }, () -> "fallback");
        check("判定正常返回", "ok", guarded);
        check("判定结束后标记清除", false, ReentrancyGuard.isActive());
        check("判定结束后深度归零", 0, ReentrancyGuard.depth());
        check("自检快照默认开启闸门（GuardState.of）", true,
                GuardState.of(true, GuardPreset.DISABLED, true, true, false, true, 5, 2,
                        java.util.Set.of(), java.util.List.of(), java.util.List.of()).enableDispatcherGate());

        // ------------------------------- 1.0.2：判定矩阵（三层共用同一套逻辑）
        GuardState.SourceFacts player = new GuardState.SourceFacts(true, true, false, false, false);
        GuardState strict = GuardState.of(true, GuardPreset.DISABLED, true, true, false, true, 5, 2,
                java.util.Set.of(), java.util.List.of(), java.util.List.of());
        check("严格默认：/give 对普通玩家 → 拦截", false,
                strict.decide(java.util.List.of("give", "@a", "stone"), 2, player).allowed());
        check("严格默认：/give 解析期 → 拦截", false, strict.decideRoot("give", 2, player).allowed());
        check("严格默认：/help 对普通玩家 → 放行", true,
                strict.decide(java.util.List.of("help"), 0, player).allowed());
        check("严格默认：/gamemode 解析期 → 拦截", false, strict.decideRoot("gamemode", 2, player).allowed());
        check("严格默认：/commandcontrol → 放行", true, strict.decideRoot("commandcontrol", 3, player).allowed());
        check("严格默认：开作弊（未达绕过等级）→ /give 仍拦截", false,
                strict.decideRoot("give", 2, new GuardState.SourceFacts(true, true, false, false, false)).allowed());
        check("达到绕过等级 → 放行", true,
                strict.decideRoot("give", 2, new GuardState.SourceFacts(true, true, false, true, false)).allowed());
        check("白名单玩家 → 放行", true,
                strict.decideRoot("give", 2, new GuardState.SourceFacts(true, true, true, false, false)).allowed());
        check("不受管辖的游戏模式 → 放行", true,
                strict.decideRoot("give", 2, new GuardState.SourceFacts(true, false, false, false, false)).allowed());
        check("控制台 → 放行", true,
                strict.decideRoot("give", 2, new GuardState.SourceFacts(false, true, false, false, true)).allowed());
        check("命令方块 → 拦截", false,
                strict.decideRoot("give", 2, new GuardState.SourceFacts(false, true, false, false, false)).allowed());
        check("总开关关闭 → 全部放行", true,
                GuardState.of(false, GuardPreset.DISABLED, true, true, false, true, 5, 2,
                                java.util.Set.of(), java.util.List.of(), java.util.List.of())
                        .decideRoot("give", 2, player).allowed());

        // ------------------------------- 参数级规则（闸门与执行层的分工）
        GuardState patterns = GuardState.of(true, GuardPreset.NO_RESTRICTION, true, true, false, true, 5, 2,
                java.util.Set.of(),
                java.util.List.of(CommandPattern.compile("tp @e")),
                java.util.List.of());
        check("不禁用指令：/tp 解析期放行（参数留给执行期判定）", true,
                patterns.decideRoot("tp", 2, player).allowed());
        check("参数级禁用 /tp @e → 拦截", false,
                patterns.decide(java.util.List.of("tp", "@e", "0", "0", "0"), 2, player).allowed());
        check("参数级禁用不影响 /tp 0 0 0", true,
                patterns.decide(java.util.List.of("tp", "0", "0", "0"), 2, player).allowed());

        // 启用列表只“加”不“减”：禁用指令下放行 /time set day，但 /time set night 仍被拦
        GuardState enabledList = GuardState.of(true, GuardPreset.DISABLED, true, true, false, true, 5, 2,
                java.util.Set.of(), java.util.List.of(),
                java.util.List.of(CommandPattern.compile("time set day")));
        check("禁用指令 + 启用列表：/time set day 放行", true,
                enabledList.decide(java.util.List.of("time", "set", "day"), 2, player).allowed());
        check("禁用指令 + 启用列表：/time set night 仍拦截", false,
                enabledList.decide(java.util.List.of("time", "set", "night"), 2, player).allowed());
        check("禁用指令 + 启用列表：/time 解析期放行（参数交给执行期）", true,
                enabledList.decideRoot("time", 2, player).allowed());

        GuardState singleDeny = GuardState.of(true, GuardPreset.NO_RESTRICTION, true, true, true, true, 5, 2,
                java.util.Set.of(), java.util.List.of(CommandPattern.compile("give")), java.util.List.of());
        check("单命令禁用模式在解析期直接截断 /give", false,
                singleDeny.decideRoot("give", 2, player).allowed());
        check("单命令禁用不影响其它命令", true, singleDeny.decideRoot("help", 0, player).allowed());

        // ------------------------------- 1.0.4 核心：不禁用指令 = 命令树零改动
        GuardState disabledPreset = GuardState.of(true, GuardPreset.DISABLED, true, true, false, true, 5, 2,
                java.util.Set.of(), java.util.List.of(), java.util.List.of());
        check("禁用指令 → 需要装闸门", true, disabledPreset.shouldGateCommandTree());
        check("不禁用指令 → 不装闸门（命令树完全不动）", false, patterns.shouldGateCommandTree());
        check("闸门开关关掉 → 不装闸门", false,
                GuardState.of(true, GuardPreset.DISABLED, true, true, false, false, 5, 2,
                        java.util.Set.of(), java.util.List.of(), java.util.List.of()).shouldGateCommandTree());
        check("不禁用指令：/tp 放行", true, patterns.decideRoot("tp", 2, player).allowed());
        check("不禁用指令：/time 放行", true,
                patterns.decide(java.util.List.of("time", "set", "night"), 2, player).allowed());
        check("不禁用指令：/give 放行", true, patterns.decideRoot("give", 2, player).allowed());

        // ------------------------------- 命令树闸门本身（泛型 S=String，完全离线）
        runCommandTreeGateTests();

        System.out.println();
        System.out.println("通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /**
     * 用真实的 {@link CommandControlConfig} 生成一份默认配置，并读回关键键。
     *
     * <p>{@code IConfigSpec$ILoadedConfig} 是 sealed 的，没法把配置“装进” spec 里，
     * 所以默认值走“生成 TOML 再读回”的路子；判定逻辑则用 {@link GuardState#of} 显式构造快照来覆盖。
     */
    private static CommentedFileConfig writeDefaultConfig() {
        try {
            Path path = Path.of("build", "selftest-defaults.toml");
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            CommentedFileConfig file = CommentedFileConfig.builder(path, TomlFormat.instance())
                    .preserveInsertionOrder()
                    .writingMode(WritingMode.REPLACE)
                    .build();
            CommandControlConfig.SPEC.correct(file, (action, p, oldValue, newValue) -> {
            });
            file.save();
            return file;
        } catch (Throwable t) {
            System.out.println("FAIL 生成默认配置失败：" + t);
            return CommentedFileConfig.builder(Path.of("build", "selftest-defaults-fallback.toml"),
                    TomlFormat.instance()).build();
        }
    }

    // CommentedFileConfig#get 是泛型方法，直接塞进 String.valueOf 会被推断成 char[] 重载，
    // 所以统一先取成 Object 再按类型转换。
    private static Object raw(CommentedFileConfig config, String path) {
        return config.get(path);
    }

    /**
     * 命令树闸门的手术测试（用 {@code CommandDispatcher<String>}，不需要任何 Minecraft 类型）。
     *
     * <p>这是 1.0.4 针对“/tp、/time 整条命令消失”事故的回归测试：包装后命令树必须仍然完整，
     * 别名（redirect，例如 /tp → /teleport）必须照常解析，原始 requirement 必须仍然生效。
     */
    private static void runCommandTreeGateTests() {
        CommandDispatcher<String> dispatcher = new CommandDispatcher<>();
        LiteralCommandNode<String> teleport = dispatcher.register(
                LiteralArgumentBuilder.<String>literal("teleport").executes(context -> 1));
        dispatcher.register(LiteralArgumentBuilder.<String>literal("tp").redirect(teleport));
        dispatcher.register(LiteralArgumentBuilder.<String>literal("give").executes(context -> 1));
        dispatcher.register(LiteralArgumentBuilder.<String>literal("time").executes(context -> 1));
        dispatcher.register(LiteralArgumentBuilder.<String>literal("help").executes(context -> 1));
        dispatcher.register(LiteralArgumentBuilder.<String>literal("commandcontrol").executes(context -> 1));
        dispatcher.register(LiteralArgumentBuilder.<String>literal("kill")
                .requires(source -> source.equals("op")).executes(context -> 1));

        java.util.Set<String> namesBefore = new java.util.LinkedHashSet<>();
        for (var node : dispatcher.getRoot().getChildren()) {
            namesBefore.add(node.getName());
        }
        int countBefore = namesBefore.size();

        CommandTreeGate.Result<String> result = CommandTreeGate.install(dispatcher,
                (name, source) -> !(name.equals("give") && source.equals("banned")),
                java.util.Set.of("commandcontrol"));

        check("闸门安装成功（未回滚）", true, result.installed());
        check("闸门没有回滚", false, result.rolledBack());
        check("根命令数量不变", countBefore, dispatcher.getRoot().getChildren().size());
        boolean allPresent = true;
        for (String name : namesBefore) {
            if (dispatcher.getRoot().getChild(name) == null) {
                allPresent = false;
            }
        }
        check("包装后每个原命令都还在树上", true, allPresent);
        check("管理命令没有被包装", false, result.wrappedNames().contains("commandcontrol"));
        check("原始 requirement 已留存（探测用）", true, result.originals().containsKey("give"));
        check("别名 /tp 仍在树上", true, dispatcher.getRoot().getChild("tp") != null);
        check("别名 /tp 仍能解析", true, parseOk(dispatcher, "tp", "player"));
        check("别名目标 /teleport 仍在树上", true, dispatcher.getRoot().getChild("teleport") != null);
        check("未被拦的命令照常可执行", 1, executeQuietly(dispatcher, "help", "banned"));
        check("闸门放行的源照常可执行", 1, executeQuietly(dispatcher, "give", "player"));
        check("闸门拦下的源：命令解析失败（等同不存在）", false, parseOk(dispatcher, "give", "banned"));
        check("闸门拦下的源：/time 等其它命令不受影响", true, parseOk(dispatcher, "time", "banned"));
        check("原始 requirement 仍然生效（/kill 需要 op）", false, parseOk(dispatcher, "kill", "player"));
        check("原始 requirement 仍然生效（/kill 对 op 放行）", true, parseOk(dispatcher, "kill", "op"));
    }

    /**
     * 解析是否“命中”了命令节点。
     *
     * <p>注意：brigadier 对 requirement 不通过的节点是<b>静默跳过</b>（不写进 exceptions），
     * 所以要靠 {@code getContext().getNodes()} 是否为空来判断——这也是客户端补全与执行的共同依据。
     */
    private static boolean parseOk(CommandDispatcher<String> dispatcher, String command, String source) {
        try {
            return !dispatcher.parse(command, source).getContext().getNodes().isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private static int executeQuietly(CommandDispatcher<String> dispatcher, String command, String source) {
        try {
            return dispatcher.execute(command, source);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String str(CommentedFileConfig config, String path) {
        Object value = raw(config, path);
        return value == null ? null : String.valueOf(value);
    }

    private static int num(CommentedFileConfig config, String path) {
        Object value = raw(config, path);
        return value instanceof Number number ? number.intValue() : Integer.MIN_VALUE;
    }

    private static Object bool(CommentedFileConfig config, String path) {
        return raw(config, path);
    }

    private static void patternMatches(String pattern, String command, boolean expected) {
        CommandPattern compiled = CommandPattern.compile(pattern);
        boolean actual = compiled != null && compiled.matches(CommandPattern.tokenize(command));
        check("模式 \"" + pattern + "\" 对 \"/" + command + "\"", expected, actual);
    }

    private static void allows(GuardPreset preset, String root, int level, boolean expected) {
        check("组 " + preset.id() + "(" + preset.displayName() + ") 对 /" + root + "（等级 " + level + "）",
                expected, preset.allows(root, level));
    }

    private static void level(String root, int expected) {
        check("权限等级表 /" + root, expected, CommandPermissions.vanillaLevel(root));
    }

    private static void resolve(String root, int expected) {
        check("resolve(/" + root + ", 兜底 2)", expected,
                CommandPermissions.resolve(root, null, false, 2));
    }

    private static void resolve(String root, int unknownLevel, int expected) {
        check("resolve(/" + root + ", 兜底 " + unknownLevel + ")", expected,
                CommandPermissions.resolve(root, null, false, unknownLevel));
    }

    private static void check(String what, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL  " + what + "  期望=" + expected + "  实际=" + actual);
        }
    }

    private SelfTest() {}
}
