# 技术说明与实现记录（Technical Notes）

> 本文是**开发者向**的长文档：NeoForge/Cloth Config API 的逐条核对结论、三层拦截的实现细节、
> 命令树手术与回滚机制、Maven 镜像与本地补丁仓方案、原版命令权限等级全表（逐条）、
> 两次线上事故（1.0.2 递归卡死、1.0.3 命令被隐藏）的取证与修复、以及完整验证清单。
>
> **普通用户请看 [README.md](../README.md)**（安装、预设选择、实战配方、命令手册、排错 FAQ）。

---

# Command Control —— NeoForge 1.21.1 命令守卫

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green.svg)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.x-orange.svg)](https://neoforged.net/)

按“预设 + 自定义列表”在**服务端**拦截/放行 Minecraft 命令。单人、局域网联机、专用服务器三种环境通用；
配置用 NeoForge 原生 `ModConfigSpec`（Configured 可直接生成游戏内菜单），另配 Cloth Config 风格的配置界面。

* 当前版本：**1.0.4**　所有者 / Owner：**lyhyy2007**　程序提供 / Program provider：**deepseek**
* 许可证：**MIT**（见 [LICENSE](LICENSE)）
* 目标版本：Minecraft **1.21.1** / NeoForge **21.1.233**（21.1.x 全线兼容，`versionRange=[21.1,)`）/ Java **21**
* 模组标识：modId `command_control`｜显示名 `Command Control`｜产物 `command-control-<版本>.jar`
  （modId 为什么不能用连字符：见 §0 第 10 条）
* **已构建产物**：`build/libs/command-control-1.0.4.jar`（本仓库保留该 jar，可直接拖进 `mods/`）
* 构建插件：`net.neoforged.moddev` **2.0.144**（NeoForm 管线）
* 可选前置：Cloth Config **15.0.140**（客户端界面）、Configured（自动生成菜单）
* 预设（1.0.4 起只有两个，**默认「禁用指令」**）：**禁用指令** = 只放行权限等级 0 的命令，其余在解析期截断；
  **不禁用指令** = 命令树零改动，`/tp`、`/time` 等所有命令照常可见可用
* 默认管辖范围（1.0.1 起）：生存/创造/冒险/旁观**全部管辖**，
  绕过等级 **5**（开作弊的玩家也照样受限），并**禁止 F3+F4 等一切游戏模式切换**
* 拦截结构：① 命令树闸门（仅「禁用指令」时装配，解析期截断）② `CommandEvent`（执行前取消）
  ③ 游戏模式切换拦截；三层共用同一套判定

---

## 0.-1 ⚠ 1.0.2 会让“进存档卡死”（MC 无响应）——**已在 1.0.3 修复，请勿继续使用 1.0.2**

**现象**：装 1.0.2 后进入存档时游戏无响应（任务管理器显示 MC 未响应），存档进不去。

**崩溃报告取证**（`错误报告-10-10-2026_18.41.12\游戏崩溃前的输出.txt`，以下 6 帧无限重复）：

```
at com.lyhyy2007.commandcontrol.CommandPermissions.resolve(CommandPermissions.java:123)
at com.lyhyy2007.commandcontrol.CommandGate.allowRoot(CommandGate.java:99)
at com.lyhyy2007.commandcontrol.CommandGate.lambda$install$0(CommandGate.java:75)   ← 被我包装过的 requirement
at com.lyhyy2007.commandcontrol.CommandPermissions.probe(CommandPermissions.java:152)
at com.lyhyy2007.commandcontrol.CommandPermissions.lambda$resolve$0(CommandPermissions.java:123)
at java.base/java.util.concurrent.ConcurrentHashMap.computeIfAbsent(ConcurrentHashMap.java:1740)
```

**根因**：1.0.2 新增的“命令树强制闸门”把根命令的 requirement 换成了
`原 requirement AND 本模组判定`；而权限探测 `probe()` 又去调用**节点当前的** requirement
（也就是我自己刚包上去的那个），于是
`allowRoot → resolve → probe → requirement → allowRoot → …` **无限递归**。
再叠加 `ConcurrentHashMap.computeIfAbsent` 的映射函数重入，本该抛 `StackOverflowError` 的场景
变成了**线程挂起** → 进存档时客户端请求命令补全会触发解析 → 整局无响应。

**1.0.3 的三道保险**（每条对应栈里的一环）：

| 保险 | 实现 | 断了哪一环 |
|---|---|---|
| 探测只测“包装前”的谓词 | `CommandGate` 包装时把原始 requirement 存进 `ORIGINAL_REQUIREMENTS`，`probe()` 经 `CommandGate.originalRequirement(root)` 取用 | 断掉 `probe → 包装后 requirement → allowRoot` |
| 判定不可重入 | 新增 `ReentrancyGuard`（ThreadLocal 深度）：判定过程中再次进入判定的调用一律走 fallback，绝不递归 | 兜住任何残余再入路径 |
| 不用 `computeIfAbsent` | 改成 `get` + `putIfAbsent`，映射函数不再重入 ConcurrentHashMap | 消除“递归变挂起”的成因 |
| 逃生开关 | 新增 `permissions.enableDispatcherGate`（默认 true）与 `/commandcontrol gate <bool>`：可关掉第一层，只保留执行层拦截（`/reload` 生效） | 再遇兼容问题也不会卡死 |

回归测试：`SelfTest` 增加 `ReentrancyGuard` 用例（含“再入必须走 fallback”），自检 **138 项全过**。

---

## 0.-2 ⚠ 1.0.3 的问题：不禁用模式下 `/tp`、`/time` 也消失（**已在 1.0.4 修复**）

**现象**（玩家反馈）：`/tp`、`/time` 等命令用不了，输入框里连补全都看不到；**即使切到“不禁用”模式也一样**。

**根因**：1.0.2/1.0.3 的“命令树闸门”**不管当前是什么预设都会包装根命令**。
包装一旦不如预期，命令就会从解析与补全里彻底消失——这是“从底层删除”，比“拦一下并提示”严重得多。

**1.0.4 的三条修法**：

| 修法 | 说明 |
|---|---|
| **闸门只在「禁用指令」预设下装配** | 新增 `GuardState#shouldGateCommandTree()`：总开关开 + 闸门开关开 + 预设为禁用指令，三者缺一不可。「不禁用指令」时**一条根命令都不包装**，命令树与 Vanilla 完全一致 |
| **手术独立成类 + 完整性校验 + 自动回滚** | 新增 `CommandTreeGate`：先记录原始节点，包装后校验“根命令数量不变、每个名字都还在、包装节点确实生效”；任何异常或不一致 → **整体回滚**并记 ERROR，命令树恢复原样 |
| **闸门异常即放行** | 包装后的 requirement 里 `try/catch`：判定抛异常时只跑原 requirement，绝不让某条命令因为本模组变成“不存在” |

**回归测试**（完全离线）：用 `CommandDispatcher<String>`（不依赖任何 Minecraft 类型）真实跑一遍手术——
`/teleport` + 别名 `/tp`（redirect）、`/give`、`/time`、`/help`、`/kill`（requires op）：

```
根命令数量不变 ✓   7 个命令包装后全部仍在树上 ✓   别名 /tp 仍能解析 ✓
管理命令未被包装 ✓   原 requirement 仍生效（/kill 对非 op 不可用、对 op 可用）✓
闸门拦下的源：/give 解析不到（等同不存在）✓   /time 不受影响 ✓
```

顺带修掉作者自己的两个测试断言错误（用 `getExceptions()` 判断无效——brigadier 对 requirement 不通过是
**静默跳过节点**，必须看 `parse(...).getContext().getNodes()`）。

---

## 0.0 为什么 1.0.0 / 1.0.1 都“实测不生效”——已用你机器上的实际文件取证

> **直接证据**（取自 `versions\山居醉梦_Neoforge_Ⅰ\config\command-control-common.toml`）：
>
> ```toml
> 	#当前启用的组（默认 DISABLED = 组1，装上即生效）：
> 	preset = "DISABLED"              # ← 新默认值确实生效了
> ...
> 	#权限等级 >= 此值的命令源直接放行。
> 	#5 = 谁都不放行（默认；开作弊的玩家等级是 4，照样受限）；
> 	# Default: 5
> 	bypassPermissionLevel = 4        # ← ★真凶：注释写着默认 5，值却是 1.0.0 时代留下的 4
> ...
> 	deniedMessage = "该命令已被 Command Control 禁用：{command}"   # ← 同样是 1.0.0 的旧文本
> ```
>
> 结论：**NightConfig 加载已存在的配置文件时只补缺失的键，绝不覆盖已有键的值。**
> 1.0.0 写下的 `bypassPermissionLevel = 4` 被 1.0.1 原样沿用；而单人开作弊 / 局域网主机 / OP 的
> 权限等级正好是 4 → 判定链第 6 步 `hasPermission(4) == true` → **自己把自己整体放行**。
> 所以无论 1.0.0 还是 1.0.1，测试者（房主）看到的都是“命令照常能用”。

1.0.2 的对策：

| # | 问题 | 1.0.2 的修法 |
|---|---|---|
| 1 | 旧配置文件锁住旧值（**真正的根因**） | 新增 `general.configVersion`（旧文件默认 0）。加载时发现 `0 < 1` 就做**一次性迁移**：`preset` 若仍是 `NO_RESTRICTION` → 改 `DISABLED`；`bypassPermissionLevel` 若 ≤4 → 改 **5**；写回文件并盖章。迁移会打 `WARN` 说明改了什么、怎么改回去 |
| 2 | 配置宽松时**静默无声**，无法判断“到底生效没有” | 快照构建后若 `blocksNothing()`（组 4 且禁用列表为空）就打 `WARN`；启动/重载/开服都打一条**状态摘要** |
| 3 | 只依赖 `CommandEvent` 一层，绕不过去就没辙 | 新增**命令树强制闸门**（`CommandGate`）：在 `RegisterCommandsEvent` 里包装每个根命令的 requirement，判定不过的命令在**解析期**就被当成不存在 → 不进入执行流程、Tab 补全里也不出现；命令方块 / 告示牌 / 函数 / 直接调 dispatcher 的模组一并截断 |
| 4 | 配置事件没送达就永久失效 | `GuardState.current()` 自愈：发现还是兜底快照但配置已加载（`SPEC.isLoaded()`）时自动重建，不依赖事件时序 |
| 5 | 判定逻辑藏在 Minecraft 类型里，无法离线验证 | 判定重构为纯函数（`SourceFacts` + `Verdict`），自检从 76 项扩到 **121 项**，覆盖三层共用的判定矩阵与真实默认值 |
| 6 | 排查手段不足 | 新增 **`/commandcontrol diag`**：逐层报告闸门/执行层/模式层的安装与拦截计数，并对 `/give`、`/gamemode`、`/tp`、`/help` 等实时算出“放行/拦截 + 原因” |

> 如果你只想手工救急（不换 jar）：把该文件的 `bypassPermissionLevel = 4` 改成 `5` 即可立刻生效。
> 装 1.0.2 后无需手工改——迁移会自己做，并在日志里说明。

---

## 0.1 1.0.1 相对 1.0.0 的改动（历史记录）

| # | 1.0.0 的问题 | 现象 | 1.0.1 的修法 |
|---|---|---|---|
| 1 | `preset` 默认是 **组 4 `NO_RESTRICTION`（不禁用）** | 装完什么都不拦，看起来“没生效” | 默认改为 **组 1 `DISABLED`**（只放行权限等级 0 的命令） |
| 2 | `bypassPermissionLevel` 默认 **4** | 单人开作弊 / 局域网主机 / OP 的权限等级就是 4 → **自己被整体放行**，怎么试都能用命令 | 默认改为 **5（谁都不放行）**；想让 OP 放行再调回 4 |
| 3 | 有“仅冒险模式生效”这个开关的语义 | 容易被理解成“只管冒险模式” | **删除该选项**，改为 4 个游戏模式开关（`restrictSurvival` / `restrictCreative` / `restrictAdventure` / `restrictSpectator`），**默认全开** |
| 4 | 只拦命令，拦不住 **F3+F4** | F3+F4 切模式走的是网络包 → `ServerPlayer.setGameMode`，**根本不经过命令系统**，`CommandEvent` 永远看不到 | 新增监听 **`PlayerEvent.PlayerChangeGameModeEvent`**（可取消），被管辖的玩家**任何**模式切换都被拦下（切旁观放行，避免硬核死亡卡死） |
| 5 | 非玩家来源一刀切放行 | 创造玩家放个命令方块就能绕过 | 拆成 `allowConsoleSources`（默认 **true**，服主兜底）与 `allowCommandBlocks`（默认 **false**，命令方块也受限） |
| 6 | 拦截只写 debug 日志，且启动日志没说明状态 | 出问题无从判断“到底加载没有” | 启动/重载/开服都打一条**状态摘要**（总开关、启用组、管辖模式、绕过等级、放行策略）；拦截命令与模式切换记 **INFO** |
| 7 | 严格组会把服主自己锁死 | 只能用外部文件改配置 | `/commandcontrol` 现在是**始终放行**的管理命令（自身仍要求权限等级 3） |

> 如果你更想要 1.0.0 那种“默认不管”的行为：`/commandcontrol preset NO_RESTRICTION`，或直接改配置文件。

---

## 0. 调研结论（先看这一段，含对原技术方案的修正）

全部结论都对着 **NeoForge 21.1.233 的真实源码 / 真实 jar** 核对过（用 `javap` 与反编译源码逐个签名确认），
没有凭记忆写 API。与常见资料 / 原始需求不符的地方如下：

| # | 结论 | 影响 |
|---|---|---|
| 1 | `net.neoforged.neoforge.event.CommandEvent` 在 21.1.x **只有一个构造参数** `ParseResults<CommandSourceStack>`，**没有 `getCommand()` 方法**。原始命令串要从 `getParseResults().getReader().getString()` 取 | 若按“`event.getCommand()` + 双参构造”写，直接编译不过 |
| 2 | `CommandEvent` 在 `Commands#performCommand(ParseResults, String)` 里、**解析完成后、执行前**触发；位于 `NeoForge.EVENT_BUS`（主事件总线）且只在逻辑服务端；实现 `ICancellableEvent`，`setCanceled(true)` 后 `performCommand` 直接 `return`（命令不执行）。`event.setException(t)` 会额外让原版把异常文本发给命令源，**但会以 ERROR 级别记日志**，所以本模组自己发提示、不设 exception | 拦截与提示的正确写法 |
| 3 | 1.21.1 的 `@EventBusSubscriber` 有 `modid()`、`value()`（`Dist[]`）与 **`bus()`** 三个属性；`bus()` 的 `AnnotationDefault` 是 **`Bus.GAME`**，**没有 `dist()`**（`javap -v` 实测）。**注意**：作者在 1.0.0/1.0.1 的文档里曾误写成“没有 `bus` 属性”，那是当时 javap 输出被截断导致的错误结论，已更正 | 写 `@EventBusSubscriber(modid=…)` 默认就注册到游戏总线，`bus` 可省略；1.0.2 索性改为在主类构造器里**显式注册**，不再依赖注解扫描 |
| 4 | Cloth Config 1.21.1 的坐标是 `me.shedaniel.cloth:cloth-config-neoforge:15.0.140`，**仓库是 `https://maven.shedaniel.me/`（或 Modrinth maven）**；`repo1.maven.org` 上**没有** `me/shedaniel` 这个 group（实测 404） | “Maven Central 坐标”那条信息是错的；15.x 最新为 15.0.140（也有 15.0.130） |
| 5 | `ModConfigEvent`（`Loading`/`Reloading`/`Unloading`）是 `IModBusEvent`，**只在 mod 事件总线**上触发，主事件总线上收不到 | 配置热重载必须用 `modEventBus.addListener` |
| 6 | `@EventBusSubscriber` 注册的监听器与 `ModConfigEvent` 的监听器**不在同一条总线**：前者主事件总线、后者 mod 事件总线，必须分开注册 | 单靠注解会漏掉配置重载 |
| 7 | 原版 1.21.1 **已经没有任何权限等级 1（Moderator）的命令**；`/seed` 是特例：`requires(p -> !notIntegratedServer \|\| p.hasPermission(2))`，即集成服务器（单人/局域网）对所有人开放、专用服务器要等级 2 | 等级表按“0 / 2 / 3 / 4”四档建模，`/seed` 运行时判定 |
| 8 | 1.21.1 的命令类在 `net/minecraft/server/commands/`（不是 `net/minecraft/commands/commands/`） | 想核对等级表请去这个包 |
| 9 | Configured：Modrinth 上 slug 为 `configured` 的项目（作者 fooeyround）**是另一个模组**（仅 Fabric 的服务器小工具）；MrCrayfish 的 Configured 在 CurseForge 发布 | 本模组只按 modId `configured` 声明**可选**依赖，没有臆造它的 `[modproperties]` 键名（见 §10） |
| 10 | **modId 不允许连字符**：FML `ModInfo` 用 `VALID_MODID = ^[a-z][a-z0-9_]{1,63}$`，不匹配直接 `throw new InvalidModFileException`（字节码 offset 50–129 实测）；允许 `-`/`.` 的是 `VALID_NAMESPACE` | 因此 modId = `command_control`，显示名/产物名 = `command-control`；写成 `command-control` 会导致拒载 |
| 11 | **BMCLAPI 缺 NeoForm 运行库**，而 `repo.stellardrift.ca/maven/internal/` 有 `neoform-runtime`，`maven.minecraftforge.net` 有它的 `mergetool` 依赖 | `build.gradle` 用「BMCLAPI + StellarDrift + MinecraftForge」三仓库叠加，`gradlew build` 无需代理即可跑通反编译管线（见 §9.2） |

权限等级（原版 `Commands` 常量，1.21.1 实测）：

```java
public static final int LEVEL_ALL = 0;         // 所有玩家
public static final int LEVEL_MODERATORS = 1;  // 1.21.1 已无命令使用
public static final int LEVEL_GAMEMASTERS = 2; // /give /tp /weather /time /execute ...
public static final int LEVEL_ADMINS = 3;      // /ban /kick /op /whitelist ...
public static final int LEVEL_OWNERS = 4;      // /stop /save-all /publish ...
```

---

## 1. 项目结构树

```
dsh-command-control-1.0.4/
├── LICENSE                                       # MIT 许可证（GitHub 识别用）
├── CHANGELOG.md                                  # 版本更新日志（1.0.0 → 1.0.4）
├── .gitignore / .gitattributes                   # GitHub 标准忽略与换行规则
├── build.gradle                                  # moddev 2.0.144 + 仓库/依赖（§2）
├── settings.gradle                               # foojay toolchain（自动取 JDK 21）
├── gradle.properties                             # 版本与 mod 元数据（mod_id=command_control）
├── gradlew / gradlew.bat / gradle/wrapper/*      # Gradle 9.2.1 wrapper
├── README.md                                     # 本文档
├── cloth-config-neoforge-15.0.140.jar            # 离线构建用的前置 jar（可选，见 §9）
├── docs/
│   └── default-config-example.toml               # 由代码生成的默认配置样例（§8）
├── local-maven/                                  # 本地补丁仓库：net.neoforged:minecraft-dependencies:1.21.1（§9.2）
│   └── net/neoforged/minecraft-dependencies/1.21.1/minecraft-dependencies-1.21.1.pom
├── tools/
│   ├── build-offline.ps1                         # 离线构建：javac + 自检 + 打包（§9.2）
│   ├── gen-minecraft-dependencies.ps1            # 从 Mojang 版本清单重建上面的补丁 pom
│   └── selftest/
│       ├── SelfTest.java                         # 纯逻辑自检（138 项）
│       ├── TomlCheck.java                        # 用 NightConfig 校验 mods.toml 元数据 + MODID 一致性
│       └── GenerateDefaultConfig.java            # 从 ModConfigSpec 生成默认配置样例
└── src/main/
    ├── java/com/lyhyy2007/commandcontrol/
    │   ├── CommandControl.java                   # @Mod 主类：注册配置、配置事件、启动刷新
    │   ├── CommandGuard.java                     # ★ CommandEvent 拦截核心（§5）
    │   ├── CommandPattern.java                   # 命令模式（词元前缀 + * / ** 通配）
    │   ├── CommandPermissions.java               # 命令根 → 权限等级表 + 命令树探测
    │   ├── GuardPreset.java                      # 四个内置启用组（§6）
    │   ├── GuardState.java                       # 配置的运行时快照（不可变，供热路径读取）
    │   ├── config/CommandControlConfig.java      # ModConfigSpec 配置定义（§7）
    │   ├── command/CommandControlCommand.java    # /commandcontrol 管理命令（OP 游戏内调控）
    │   └── client/
    │       ├── CommandControlClientSetup.java          # Dist.CLIENT：注册配置界面扩展点
    │       └── CommandControlClothScreen.java          # Cloth Config ConfigBuilder 界面（§7）
    └── resources/META-INF/neoforge.mods.toml     # 模组元数据与依赖（§3）
```

---

## 2. build.gradle 完整配置

```groovy
plugins {
    id 'java-library'
    id 'maven-publish'
    id 'net.neoforged.moddev' version '2.0.144'
    id 'idea'
}

version = mod_version
group = mod_group_id
base { archivesName = mod_archive_name }   // 产物 command-control-1.0.1.jar

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

neoForge {
    version = project.neo_version                     // 21.1.233（gradle.properties）
    runs {
        client { client() }
        server { server(); programArgument '--nogui' }
        data   { data() }
    }
    mods {
        "${mod_id}" { sourceSet sourceSets.main }
    }
}

// ---------------------------------------------------------------------------
// 依赖仓库（Gradle 在某个仓库 404 时会继续试下一个，所以可以多个镜像叠加）
//   1. BMCLAPI：NeoForge 发布件（neoforge userdev/universal、AutoRenamingTool 等）
//   2. StellarDrift internal maven：**含 NeoForm 运行库**（neoform-runtime 等），
//      补上 BMCLAPI 缺失的开发期工具，让 moddev 的反编译管线能跑完
//   3. MinecraftForge maven：neoform-runtime 的运行期依赖（mergetool / installertools）
//   4. Cloth Config / Modrinth / Maven Central
// 官方 maven.neoforged.net 在部分网络下不可达，插件会自动加上它，这里把它摘掉。
// ---------------------------------------------------------------------------
repositories {
    maven {
        name = 'BMCLAPI NeoForged Mirror'
        url = 'https://bmclapi2.bangbang93.com/maven/'
        content { includeGroupByRegex 'net\\.neoforged.*' }
    }
    maven {
        name = 'StellarDrift (NeoForm runtime)'
        url = 'https://repo.stellardrift.ca/maven/internal/'
        content { includeGroupByRegex 'net\\.neoforged.*' }
    }
    maven {
        name = 'MinecraftForge'
        url = 'https://maven.minecraftforge.net/'
        content { includeGroupByRegex 'net\\.minecraftforge.*' }
    }
    maven { name = 'Shedaniel (Cloth Config)'; url = 'https://maven.shedaniel.me/' }
    maven { name = 'Modrinth';                url = 'https://api.modrinth.com/maven' }
    mavenCentral()
}

def dropUnreachableNeoForgedRepo = {
    repositories.removeIf { repo ->
        repo instanceof MavenArtifactRepository && repo.url != null &&
                repo.url.toString().contains('maven.neoforged.net')
    }
}
dropUnreachableNeoForgedRepo()
afterEvaluate { dropUnreachableNeoForgedRepo() }

dependencies {
    // Cloth Config 只做客户端界面：compileOnly + runtimeOnly，不打进产物、不是硬前置。
    compileOnly "me.shedaniel.cloth:cloth-config-neoforge:${cloth_config_version}"
    runtimeOnly "me.shedaniel.cloth:cloth-config-neoforge:${cloth_config_version}"
}

sourceSets.main.resources { srcDir 'src/generated/resources' }

// 只对 mods.toml 展开 ${...}；其它资源原样复制
def props = project.properties
processResources {
    filesMatching('META-INF/neoforge.mods.toml') { expand props }
}

jar {
    manifest {
        attributes([
                "Specification-Title"     : mod_id,
                "Specification-Vendor"    : mod_authors,
                "Specification-Version"   : "1",
                "Implementation-Title"    : project.name,
                "Implementation-Version"  : project.jar.archiveVersion,
                "Implementation-Vendor"   : mod_authors,
                "Implementation-Timestamp": new Date().format("yyyy-MM-dd'T'HH:mm:ssZ")
        ])
    }
}
```

`gradle.properties`（关键项）：

```properties
minecraft_version=1.21.1
neo_version=21.1.233
minecraft_version_range=[1.21.1]
neo_version_range=[21.1,)
cloth_config_version=15.0.140
mod_id=command_control
mod_name=Command Control
mod_archive_name=command-control
mod_version=1.0.4
mod_group_id=com.lyhyy2007.commandcontrol
mod_authors=lyhyy2007
mod_license=MIT
mod_description=Command guard: per-preset allow/deny control for vanilla and modded commands, ...
```

---

## 3. neoforge.mods.toml

```toml
modLoader="javafml"          # 1.21.1 必须显式声明，否则客户端拒载（Missing ModLoader）
loaderVersion="[1,)"
license="${mod_license}"

[[mods]]
modId="${mod_id}"
version="${mod_version}"
displayName="${mod_name}"
authors="${mod_authors}"
description='''
${mod_description}
'''

[[dependencies.${mod_id}]]
modId="neoforge"
type="required"
versionRange="${neo_version_range}"
ordering="NONE"
side="BOTH"

[[dependencies.${mod_id}]]
modId="minecraft"
type="required"
versionRange="${minecraft_version_range}"
ordering="NONE"
side="BOTH"

# Cloth Config：可选，纯客户端界面
[[dependencies.${mod_id}]]
modId="cloth_config"
type="optional"
versionRange="[15,)"
ordering="AFTER"
side="CLIENT"

# Configured：可选，自动生成游戏内配置菜单
[[dependencies.${mod_id}]]
modId="configured"
type="optional"
versionRange="*"
ordering="AFTER"
side="BOTH"

# Configured 的界面背景自定义（键名请对照所用 Configured 版本，确认后再启用）
# [modproperties.command_control]
# configured:background="minecraft:textures/block/stone.png"
```

> 为什么这两个前置是 `optional`：Cloth Config 只提供客户端 GUI，Configured 只是额外的编辑入口。
> 命令拦截完全在服务端、且不依赖它们；写成 `required` 会让纯净服务器无法启动。

---

## 4. 核心类清单及职责

| 类 | 职责 | 关键 API |
|---|---|---|
| `CommandControl` | `@Mod` 入口。把 `CommandControlConfig.SPEC` 注册为 COMMON 配置；在 **mod 总线**监听 `ModConfigEvent.Loading/Reloading` 刷新快照；在主总线监听 `ServerStartedEvent` | `ModContainer#registerConfig(ModConfig.Type, IConfigSpec, String)`、`IEventBus#addListener` |
| `CommandGuard` | 拦截核心。注册 `/commandcontrol`、处理 `CommandEvent`（命令）与 `PlayerEvent.PlayerChangeGameModeEvent`（F3+F4 / 模式切换）；判定顺序：管理命令 → 非玩家来源分类 → 白名单 → 游戏模式管辖 → 绕过等级 → 启用列表 → 禁用列表 → 启用组 | `@EventBusSubscriber(modid=…)`、`CommandEvent#getParseResults/setCanceled`、`PlayerChangeGameModeEvent#setCanceled`、`CommandSourceStack#source`（区分控制台与命令方块） |
| `CommandPattern` | 一条命令模式：按空白切词元，支持前缀匹配、`*`（一个词元）、`**`（末尾任意个），忽略大小写并兼容 `minecraft:` 命名空间 | — |
| `CommandPermissions` | 原版命令根 → 权限等级静态表（源码核对）；`/seed` 特例；对未收录命令（模组命令）用命令树 requirement 探测最小可用等级，结果缓存 | `MinecraftServer#createCommandSourceStack`、`CommandSourceStack#withPermission`、`CommandNode#getRequirement` |
| `GuardPreset` | 四个内置启用组：允许的等级 + 额外命令集 | — |
| `GuardState` | 配置的**不可变运行时快照**（`volatile`），把 TOML 值编译成 `Set`/`Pattern` 列表；配置未加载时落到“不拦截”的安全兜底 | `CommandControlConfig.*#get` |
| `CommandControlConfig` | `ModConfigSpec` 定义 + `save()`；默认组 4 | `ModConfigSpec.Builder`、`defineEnum`、`defineListAllowEmpty(..., Supplier, Predicate)`、`defineInRange` |
| `CommandControlCommand` | `/commandcontrol status\|preset\|toggle\|allow\|deny\|whitelist\|info`，等级 3 起；改完即写盘 + 刷新，实时生效 | `Commands.literal/argument`、`SharedSuggestionProvider#suggest` |
| `CommandControlClientSetup` | `Dist.CLIENT`：检测到 Cloth Config 就把界面注册到模组列表的“配置”按钮 | `IConfigScreenFactory`、`ModContainer#registerExtensionPoint(Class, T)` |
| `CommandControlClothScreen` | Cloth `ConfigBuilder` 界面：读写**同一份** `ModConfigSpec`，保存时落盘 + 刷新 | `ConfigBuilder#create/getOrCreateCategory/entryBuilder/build`、`startEnumSelector/startStrList/startIntSlider/startStrField` |

> 设计要点：**只有一份配置数据**（ModConfigSpec）。Configured 自动菜单、Cloth 界面、`/commandcontrol` 命令、
> 手改 TOML 四条入口写的都是它，不存在两套配置互相覆盖的问题。

---

## 5. 拦截逻辑（伪代码，1.0.1）

```
事件 A：CommandEvent（主事件总线，仅逻辑服务端，可取消）
时机：Commands#performCommand 中，命令解析完成、尚未执行

onCommand(event):
    state = GuardState.current()                  # 配置快照，O(1)
    if not state.enabled:                     return  # 1) 总开关
    parse = event.getParseResults()
    source = parse.getContext().getSource()        # CommandContextBuilder#getSource
    tokens = tokenize(parse.getReader().getString())  # 原版命令串（无 getCommand()）
    if tokens.isEmpty():                      return
    root = normalize(tokens[0])                    # 去掉命名空间、转小写

    if state.isManageCommand(root):           return  # 2) /commandcontrol 始终放行
                                                      #    （它自身在 Brigadier 里要求权限等级 3）

    player = source.getPlayer()                        # 非玩家来源为 null
    if player == null:                                 # 3) 非玩家来源分开判断
        if isConsoleLike(source):                       #    控制台 / RCON（source 是
            if state.allowConsoleSources:     return    #    MinecraftServer 或 RconConsoleSource）
        else:                                           #    命令方块 / 命令方块矿车
            if state.allowCommandBlocks:      return
    else:
        if state.isWhitelisted(player):       return     # 4) 白名单（用户名或 UUID）
        if not state.isRestrictedGameMode(player.gameMode.getGameModeForPlayer()):
                                              return     # 5) 生存/创造/冒险/旁观（默认全管）

    if source.hasPermission(state.bypassPermissionLevel): return  # 6) 默认 5 = 谁都不放行
                                                                 #    （开作弊的玩家等级是 4，照样受限）

    if state.matchesEnabled(tokens):          return             # 7) 自定义启用列表（最高优先级）
    if state.matchesDisabled(tokens):         deny("自定义禁用列表"); return

    level = CommandPermissions.resolve(root, source,
                state.useDispatcherProbe, state.unknownCommandLevel)
                #  原版：静态表（0/2/3/4）；/seed：集成服=0，专用服=2
                #  模组命令：用 source.withPermission(0..4) 伪造命令源，测根节点 requirement，
                #            取最小可用等级并缓存；探测失败/都不通过 → unknownCommandLevel
    if state.preset().allows(root, level):    return             # 8) 启用组：等级 0 或组内额外命令
    deny("启用组 N，/{root} 需要权限等级 L")

deny(reason):
    event.setCanceled(true)                                      # 命令不会执行
    if source 是玩家: source.sendFailure(Component.literal(state.deniedMessageFor(tokens)))
    LOGGER.info("拦截命令 /{}（{}）", tokens, reason)

事件 B：PlayerEvent.PlayerChangeGameModeEvent（可取消）—— 这是拦住 F3+F4 的关键
        F3+F4 在客户端只发一个“改游戏模式”的网络包，服务端最终调用 ServerPlayer#setGameMode，
        全程不经过命令系统，所以 CommandEvent 永远看不到它。

onGameModeChange(event):
    state = GuardState.current()
    if not state.enabled or not state.protectGameModeSwitch:      return
    if event.getEntity() 不是 ServerPlayer:                        return
    if current == target:                                         return
    if not state.isRestrictedGameMode(current):                    return   # 不在管辖范围
    if state.isWhitelisted(player):                                return
    if player.createCommandSourceStack().hasPermission(bypass):    return   # 默认 5 → 不放行
    if target == SPECTATOR:                                        return   # 降级放行（硬核死亡等）
    event.setCanceled(true)                                       # 模式不会改变
    player.displayClientMessage(gameModeDeniedMessage)
    LOGGER.info("拦截游戏模式切换：{} -> {}", current, target)
```

对应的真实代码见 `src/main/java/com/lyhyy2007/commandcontrol/CommandGuard.java`。

---

## 6. 两个预设（1.0.4 起）→ 权限等级 → 允许的命令

| 编号 | 名称（枚举） | 允许的权限等级 | 命令树闸门 | 效果 |
|---|---|---|---|---|
| 1 | **禁用指令** `DISABLED`（**默认**） | 仅 **0** | **安装**：其余命令在解析期就被截断，Tab 补全里也不出现 | 只放行下面 9 个等级 0 命令 + `/commandcontrol` |
| 2 | **不禁用指令** `NO_RESTRICTION` | **0–4 全部** | **不安装**：命令树零改动 | 所有命令照常可见可用；`disabledCommands` 仍在执行前精确拦截 |

“禁用指令”里被放行的**等级 0 命令**（原版源码核对，9 个，含别名）：

```
help, list, me, msg, tell, w, teammsg, tm, trigger
```

> 原来的「仅传送」「传送+天气+时间」两个预设**已删除**：旧配置文件里若还写着
> `TELEPORT_ONLY` / `TELEPORT_WEATHER_TIME`，会被 NightConfig 按“非法枚举值”自动纠正为
> 新默认值 `DISABLED`（迁移日志会说明）。想要“传送可用”就选「不禁用指令」，再用
> `disabledCommands` 精确禁掉不想要的命令（例如 `give`、`op`）。

原始命令串按**词元前缀**匹配（`give` 命中 `/give @a stone`，但不命中 `/giver`），也支持参数级写法：

```
/give                         → 模式 "give"
/give @a diamond 3            → 模式 "give @a diamond"（前三段必须一致）
/tp @p 0 0                    → 模式 "tp * 0 0"（* = 任意一个词元）
/say hello world 123          → 模式 "say hello **"（** = 末尾起任意个词元）
/ftbteams party create my     → 模式 "ftbteams party create"（模组多级子命令同样适用）
```

**原版命令权限等级全表（1.21.1 源码提取，本模组的判定依据）**

| 等级 | 命令 |
|---|---|
| 0 | help, list, me, msg, tell, w, teammsg, tm, trigger |
| 1 | 无（1.21.1 原版已无等级 1 命令） |
| 2 | advancement, attribute, bossbar, clear, clone, damage, data, datapack, debugmobspawning, debugpath, defaultgamemode, difficulty, effect, enchant, execute, experience, fill, fillbiome, forceload, function, gamemode, gamerule, give, item, kill, locate, loot, particle, place, playsound, random, recipe, reload, return, ride, say, schedule, scoreboard, seed*, serverpack, setblock, setworldspawn, spawn_armor_trims, spawnpoint, spectate, spreadplayers, stopsound, summon, tag, team, teleport, tellraw, time, title, tp, warden_spawn_tracker, weather, worldborder, xp |
| 3 | ban, ban-ip, banlist, debug, debugconfig, deop, kick, op, pardon, pardon-ip, raid, setidletimeout, tick, transfer, whitelist |
| 4 | jfr, perf, publish, save-all, save-off, save-on, stop |

\* `/seed` 在集成服务器（单人/局域网）对所有人开放，专用服务器需等级 2。

> 组 1~3 同时也会**拦掉 `/execute`、`/function` 这类“包装型”命令**（它们是等级 2、不在允许集里），
> 这正是避免用 `/execute run give …` 绕过限制的关键。但模组自带的“一个命令内部再执行别的命令”
> 无法被本模组感知——那属于模组自身权限设计，必要时把它们加进 `disabledCommands`。

---

## 7. 配置系统（Cloth Config + Configured）

### 7.1 分工

* **数据源**：`config/commandcontrolConfig.java` 的 `ModConfigSpec`（类型 `COMMON`），文件 `config/command-control-common.toml`。
  * COMMON 配置在单人 / 局域网 / 专用服务器都由**逻辑服务端**加载，而拦截发生在服务端 → 读到的永远是服务端自己的值；
  * 它属于 NeoForge 受支持的配置系统 → **Configured 能自动为它生成游戏内菜单**，模组侧零额外代码；
  * 文件被外部修改时 NeoForge 的配置监听会触发 `ModConfigEvent.Reloading` → 本模组立刻刷新快照（热重载）。
* **Cloth Config**：`client/commandcontrolClothScreen.java`，作为“另一个编辑入口”（没装 Configured 时也能在
  模组列表点“配置”）。它用 `ConfigBuilder` 构建界面，`setSaveConsumer` 写回**同一个** `ModConfigSpec`，
  保存时 `SPEC.save()` + `GuardState.refresh()`。
* **游戏内调控**：`/commandcontrol`（等级 3 起）供服主 / 联机主机在服务器里直接调，改完写盘并立即生效。

### 7.2 配置项一览

| 配置键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `general.enabled` | bool | `true` | 总开关 |
| `general.configVersion` | int | `0` | 配置结构版本，模组自动维护；旧文件（0）加载时做一次性迁移。**勿手工改小** |
| `general.preset` | enum | **`DISABLED`** | 启用组：`DISABLED`（组1，只放行等级 0）/ `TELEPORT_ONLY` / `TELEPORT_WEATHER_TIME` / `NO_RESTRICTION`（组4，不禁用） |
| `general.restrictSurvival` | bool | `true` | 管辖生存模式 |
| `general.restrictCreative` | bool | `true` | 管辖创造模式（开着作弊的房主也在这里管） |
| `general.restrictAdventure` | bool | `true` | 管辖冒险模式 |
| `general.restrictSpectator` | bool | `true` | 管辖旁观模式 |
| `general.protectGameModeSwitch` | bool | `true` | 拦住 F3+F4、`/gamemode` 与其它模组的 `setGameMode`（切旁观放行） |
| `lists.disabledCommands` | 字符串列表 | `[]` | 自定义禁用（支持参数、`*`、`**`） |
| `lists.enabledCommands` | 字符串列表 | `[]` | 自定义启用（优先级最高） |
| `lists.whitelistPlayers` | 字符串列表 | `[]` | 白名单玩家（用户名或 UUID），绕过全部限制（含模式切换） |
| `permissions.bypassPermissionLevel` | int 0–5 | **`5`** | 权限等级 ≥ 此值直接放行；**5 = 谁都不放行**（开作弊的玩家是等级 4，照样受限）；`4` = OP/主机放行 |
| `permissions.unknownCommandLevel` | int 0–5 | `2` | 未收录命令（含模组命令）的兜底等级 |
| `permissions.allowConsoleSources` | bool | `true` | 放行控制台 / RCON（服主兜底入口） |
| `permissions.allowCommandBlocks` | bool | **`false`** | 放行命令方块 / 命令方块矿车；默认关闭，创造玩家不能靠它绕过 |
| `permissions.useDispatcherProbe` | bool | `true` | 用命令树探测模组命令的真实权限等级 |
| `permissions.enableDispatcherGate` | bool | `true` | 第一层：命令树强制闸门（解析期截断）。出兼容问题可关掉，仅保留执行层 |
| `messages.deniedMessage` | string | `该命令已被 Command Control 禁用：{command}（当前启用组：{preset}）` | 提示语，占位符 `{command}` / `{root}` / `{preset}` |
| `messages.gameModeDeniedMessage` | string | `游戏模式切换已被 Command Control 禁用` | 模式切换被拦时的提示 |

> 1.0.0 的 `general.onlyInAdventureMode` 与 `permissions.allowNonPlayerSources` **已移除**：
> 旧配置文件里残留的这两行会被忽略（不影响启动），但建议删掉以免误解。

**游戏内调控命令**（需权限等级 3，管理员 / 主机 / 开作弊的房主）：

```
/commandcontrol status                           状态（含署名、版本、管辖模式、绕过等级）
/commandcontrol preset <1-4|名称>                 切换启用组
/commandcontrol toggle <true|false>               总开关
/commandcontrol gamemodeguard <true|false>        是否拦住 F3+F4 / 模式切换
/commandcontrol gate <true|false>                开关第一层命令树闸门（改完 /reload 生效）
/commandcontrol bypass <0-5>                      绕过所需权限等级（5=开作弊也不放行）
/commandcontrol restrict <survival|creative|adventure|spectator|all> <true|false>
/commandcontrol allow|deny add|remove|list ...    自定义启用/禁用列表
/commandcontrol whitelist add|remove|list         白名单
/commandcontrol info                              原版命令权限等级表摘要
```

> `/commandcontrol` 本身**不受启用组限制**（否则严格组会把服主锁死），但它的权限等级要求照旧生效。

### 7.3 Cloth 界面关键代码

```java
ConfigBuilder builder = ConfigBuilder.create()
        .setParentScreen(parent)
        .setTitle(Component.literal("Command Control 命令守卫"))
        .setSavingRunnable(() -> { CommandControlConfig.save(); GuardState.refresh(); });
ConfigEntryBuilder entries = builder.entryBuilder();

ConfigCategory general = builder.getOrCreateCategory(Component.literal("常规"));
general.setDescription(new FormattedText[] { Component.literal("…") });   // 注意：形参是数组
general.addEntry(entries
        .startEnumSelector(Component.literal("启用组"), GuardPreset.class, CommandControlConfig.PRESET.get())
        .setDefaultValue(GuardPreset.NO_RESTRICTION)
        .setEnumNameProvider(v -> { GuardPreset p = (GuardPreset) v;
            return Component.literal(p.id() + ". " + p.displayName()); })
        .setSaveConsumer(v -> CommandControlConfig.PRESET.set(v))
        .build());
general.addEntry(entries
        .startBooleanToggle(Component.literal("总开关"), CommandControlConfig.ENABLED.get())
        .setDefaultValue(true)
        .setSaveConsumer(v -> CommandControlConfig.ENABLED.set(v))
        .build());
// 列表：startStrList(Component, List<String>)；滑条：startIntSlider(Component, int, int, int)
// 文本框：startStrField(Component, String)；工具提示：setTooltip(Component...)
return builder.build();
```

> 上述签名全部用 `javap` 对 `cloth-config-neoforge-15.0.140.jar` 核对过；两个易踩的坑：
> `ConfigCategory#setDescription` 形参是 `FormattedText[]`（不是可变参数，单个 `Component` 传不进去）；
> `ModContainer#registerExtensionPoint` 有 `(Class,T)` 与 `(Class,Supplier<T>)` 两个重载，
> 直接传 lambda 会歧义，需先声明成 `IConfigScreenFactory` 变量。

---

## 8. 默认配置文件示例

下面是**由代码真实生成**的 `config/command-control-common.toml`（`tools/selftest/GenerateDefaultConfig.java` 输出，
同时存档在 `docs/default-config-example.toml`），默认组 1「禁止命令」、四个游戏模式全部管辖、绕过等级 5（开作弊也受限）：

```toml

#Command Control —— 服务端命令守卫：按“启用组 + 自定义列表”决定哪些命令可用。
#所有者：lyhyy2007　程序提供：deepseek
[general]
	#总开关。false 时本模组完全不干预任何命令。
	enabled = true
	#当前启用的组（默认 DISABLED = 组1，装上即生效）：
	#DISABLED=组1(仅权限等级0) / TELEPORT_ONLY=组2(仅传送) /
	#TELEPORT_WEATHER_TIME=组3(传送+天气+时间) / NO_RESTRICTION=组4(不禁用)
	#Allowed Values: DISABLED, TELEPORT_ONLY, TELEPORT_WEATHER_TIME, NO_RESTRICTION
	preset = "DISABLED"
	#管辖生存模式的玩家。
	restrictSurvival = true
	#管辖创造模式的玩家（开着作弊的房主也在这里管）。
	restrictCreative = true
	#管辖冒险模式的玩家。
	restrictAdventure = true
	#管辖旁观模式的玩家。
	restrictSpectator = true
	#禁止被管辖的玩家切换游戏模式：F3+F4、/gamemode 以及其它模组调用 setGameMode 都会被拦。
	#例外：切到旁观模式（例如硬核死亡后的强制旁观）一律放行。
	protectGameModeSwitch = true

#自定义命令列表：支持单个命令与带参数的命令。
#语法：give  或  give @a diamond  或  tp * 0 0  或  say hello **
#* 匹配任意一个词元；** 只能放末尾，匹配其后全部词元。
[lists]
	#禁用列表：命中即拦截（白名单玩家与 bypassPermissionLevel 仍然优先放行）。
	disabledCommands = []
	#启用列表：命中即放行，优先级高于禁用列表与启用组。
	enabledCommands = []
	#白名单玩家：填用户名或 UUID，绕过全部限制（含启用组与模式切换限制）。
	whitelistPlayers = []

#权限相关设置。
[permissions]
	#权限等级 >= 此值的命令源直接放行。
	#5 = 谁都不放行（默认；开作弊的玩家等级是 4，照样受限）；
	#4 = 服主/联机主机/OP 放行；0 = 连控制台都受限。
	# Default: 5
	# Range: 0 ~ 5
	bypassPermissionLevel = 5
	#未收录命令（含模组命令）的兜底权限等级。组 1~3 按此判定，组 4 无视。
	# Default: 2
	# Range: 0 ~ 5
	unknownCommandLevel = 2
	#放行服务器控制台与 RCON（服主在服务器上管理时的兜底入口，建议保持 true）。
	allowConsoleSources = true
	#放行命令方块 / 命令方块矿车。默认 false：创造模式玩家也不能靠命令方块绕过限制。
	#老地图/机器依赖命令方块时再打开。
	allowCommandBlocks = false
	#对未收录命令用命令树探测真实权限等级；探测失败时用 unknownCommandLevel。
	useDispatcherProbe = true

#提示语设置。
[messages]
	#被拦截时发给玩家的提示。占位符：{command} = 完整命令，{root} = 命令名，{preset} = 当前启用组。
	deniedMessage = "该命令已被 Command Control 禁用：{command}（当前启用组：{preset}）"
	#游戏模式切换被拦截时的提示。
	gameModeDeniedMessage = "游戏模式切换已被 Command Control 禁用"
```

典型改法示例：

```toml
[general]
	preset = "TELEPORT_WEATHER_TIME"   # 组 3：只放行等级 0 + 传送 + 天气 + 时间
[lists]
	disabledCommands = ["tp @e", "say **"]   # 精确禁掉“对实体传送”和所有 say
	enabledCommands  = ["time set day"]      # 单独放开一条
	whitelistPlayers = ["Steve", "069a79f4-44e9-4726-a5be-fca90e38aaf5"]
[permissions]
	bypassPermissionLevel = 5                # 连服主也一起限制
```

---

## 9. 测试验证步骤

### 9.1 环境

| 项 | 值 |
|---|---|
| JDK | 21（`JAVA_HOME` 指向 JDK 21，构建脚本也要求） |
| Gradle | 项目自带 wrapper 9.2.1 |
| 模组端 | 客户端 + 服务端都要放（拦截在服务端，客户端只多了配置界面） |

### 9.2 构建

**方式 A：标准 Gradle 构建（推荐）**

```bat
gradlew.bat build            :: 产物 build\libs\command-control-1.0.4.jar（gradlew dist 会复制到 dist\）
gradlew.bat runClient        :: 开发环境起客户端
gradlew.bat runServer        :: 开发环境起服务端（首次记得 run\eula.txt 改 true）
```

> 本机**无法访问 `maven.neoforged.net`**（连接超时），所以 `build.gradle` 里换成了「可用镜像 + 一个本地补丁仓库」，
> 并把插件自动加入的官方仓库摘掉。各仓库分工（全部实测）：
>
> | 仓库 | 提供什么 | 备注 |
> |---|---|---|
> | `bmclapi2.bangbang93.com/maven/` | `net.neoforged:neoforge:21.1.233`（userdev / universal / sources）、`AutoRenamingTool` | 取不到 `neoform-runtime` / `installertools`（404） |
> | `repo.stellardrift.ca/maven/internal/` | **`net.neoforged:neoform-runtime:2.0.24`**（含 `-all.jar`）、`cpw.mods:modlauncher` / `securejarhandler` / `bootstraplauncher` | 补上 BMCLAPI 缺口的关键 |
> | `maven.minecraftforge.net/` | `net.minecraftforge:mergetool:1.1.7` 等 Forge 侧工具 | 注意 `cpw.mods:*` **不在** Forge maven 上 |
> | `libraries.minecraft.net/` | `com.mojang:brigadier` / `datafixerupper` / `authlib` / `logging` 等 Minecraft 库 | Mojang 官方库仓库 |
> | `local-maven/`（本工程内） | `net.neoforged:minecraft-dependencies:1.21.1` | 见下方说明 |
> | `maven.shedaniel.me/`、`api.modrinth.com/maven`、`mavenCentral()` | Cloth Config、其它依赖 | — |
>
> **关于 `local-maven/`**：`net.neoforged:minecraft-dependencies:1.21.1` 是 `net.neoforged:neoform` 的传递依赖，
> 它只发布在官方 maven 上，BMCLAPI / StellarDrift / Central / 各中国镜像**全部 404** 实测确认。
> 它的内容就是「该版本 Minecraft 的库列表」，而这份数据的权威来源是 Mojang 自己的版本清单，
> 所以本工程用 `tools/gen-minecraft-dependencies.ps1` 从 `minecraft_1.21.1_version_manifest.json`
> 重建了这个 pom（47 个非 natives 依赖），放在 `local-maven/` 并由 `repositories` 里排在最前的
> `Local NeoForged shim` 接管。这是**从权威数据重建**，不是手写臆造；脚本可随时重跑复核。
>
> 也就是说：**NeoForm 反编译管线（`createMinecraftArtifacts`）能跑通**，无需代理/VPN。
> 首次构建要把 Minecraft 反编译一遍（约 5364 个源文件，几十分钟量级），之后有缓存就很快；
> 另有一条不依赖 Gradle 的离线构建路线（方式 B）。

**方式 B：离线构建脚本（不依赖 Gradle/NeoForm，本机实测通过）**

```powershell
# 需要：本机装过 NeoForge 1.21.1（libraries 齐全）+ Cloth Config jar + 一份映射后的 Minecraft 类 jar
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build-offline.ps1
```

脚本流程：组装 1.21.1 专用类路径 → `javac` 编译 `src/main/java` → 运行 59 项逻辑自检 →
用 NightConfig 校验 `neoforge.mods.toml`（modLoader/license/依赖/**与 Java 侧 MODID 常量一致**）→
生成默认配置样例 → 打包 `dist\command-control-1.0.2-offline.jar`。

本次实际执行结果（两条路都实测通过）：

**方式 A：Gradle（产物 `build\libs\command-control-1.0.4.jar`，63.9 KB —— 本仓库已保留）**

```
> Task :createMinecraftArtifacts        # NeoForm：反编译 + 打补丁 + 重编译 5364 个源文件
> Task :compileJava
> Task :processResources
> Task :jar
BUILD SUCCESSFUL
```

**方式 B：离线脚本（产物 `dist\command-control-1.0.2-offline.jar`，42.2 KB）**

```
类路径 jar 数量：139
编译完成：10 个 class
通过 138 项，失败 0 项
自检通过
modLoader     = javafml
loaderVersion = [1,)
license       = All Rights Reserved
mods[0]       = modId=command_control version=1.0.4 displayName=Command Control
dependency    = neoforge type=required range=[21.1,)
dependency    = minecraft type=required range=[1.21.1]
dependency    = cloth_config type=optional range=[15,)
dependency    = configured type=optional range=*
mods.toml 校验通过
默认配置已生成：…\docs\default-config-example.toml（补正 15 处）
产物：…\dist\command-control-1.0.2-offline.jar
```

### 9.3 游戏内验证清单

装好后（`mods/` 放 `command-control-*.jar`，客户端可另放 Cloth Config / Configured），按顺序验证：

| # | 场景 | 操作 | 期望结果 |
|---|---|---|---|
| 1 | 开箱默认（1.0.1） | 不装任何配置，进游戏，用**开作弊的房主**试 `/give` | `/give` **被拦**（默认组 1 + 绕过等级 5）；日志出现状态摘要：`启用组=1.禁止命令，管辖模式=生存 / 创造 / 冒险 / 旁观，绕过等级=5` |
| 2 | 配置生成 | 启动一次 | 生成 `config/command-control-common.toml`，内容同 §8 |
| 3 | 组 1 | `/commandcontrol preset 1`，再用**非 OP 玩家**执行 `/help` | `/help` 正常；`/give` 被拦，收到提示“该命令已被 Command Control 禁用：/give …” |
| 4 | 组 2 | `/commandcontrol preset 2` | 非 OP 的 `/tp`、`/teleport`、`/spreadplayers` 可用；`/weather`、`/give` 被拦 |
| 5 | 组 3 | `/commandcontrol preset 3` | `/tp`、`/weather`、`/time` 可用；`/give`、`/execute` 被拦 |
| 6 | 白名单 | `/commandcontrol whitelist add <玩家名>` | 该玩家在组 1 下依然能用全部命令 |
| 7 | 绕过等级 | 把 `bypassPermissionLevel` 改成 5 | 服主/主机自己也被限制（组 1 下连 `/give` 都不行） |
| 8 | 参数级过滤 | `/commandcontrol deny add tp @e` | `/tp @e 0 0 0` 被拦，`/tp 0 0 0` 仍可用 |
| 9 | 启用覆盖 | `/commandcontrol allow add time set day`（组 1 下） | 只有 `/time set day` 可用，`/time set night` 仍被拦 |
| 10 | 仅冒险模式 | 打开 `onlyInAdventureMode` | 创造模式玩家不受限；切成冒险模式后按启用组受限 |
| 11 | 模组命令 | 装一个带命令的模组，组 1 下执行其命令 | 默认被拦（`unknownCommandLevel=2`）；探测开启时按命令树真实等级判定 |
| 12 | 配置热重载 | 游戏运行时手改 `command-control-common.toml` 保存 | 日志出现“配置已重载”，新规则立即生效，无需重启 |
| 13 | 命令方块/控制台 | 用命令方块执行 `/give` | 默认放行（`allowNonPlayerSources=true`）；改 `false` 后也被拦 |
| 14 | Cloth 界面 | 装 Cloth Config，模组列表点 Command Control 的“配置” | 出现 `常规 / 自定义列表 / 权限与判定 / 提示语` 四个分类，保存后 TOML 同步变化 |
| 15 | Configured | 装 Configured，打开其菜单找到 Command Control | 自动出现配置项（无需模组侧代码）；改动写回同一份 TOML |
| 16 | 专用服务器 | 服务端单独装模组 | 服务器正常启动，不因缺少客户端前置报错；玩家侧无需装模组也能被限制 |
| 17 | **全模式覆盖** | 依次在生存 / 创造 / 冒险 / 旁观下用普通玩家执行 `/give` | 四种模式下**都被拦**（默认 `restrict*` 全开）；用 `/commandcontrol restrict creative false` 后创造模式放行 |
| 18 | **F3+F4 模式切换** | 被管辖的玩家按 F3+F4 | 模式**不变**，提示“游戏模式切换已被 Command Control 禁用”，日志记 INFO；用 `/commandcontrol gamemodeguard false` 后可切换 |
| 19 | **开作弊也不放行** | 单人开作弊（权限等级 4）后试 `/give`；再 `/commandcontrol bypass 4` | 默认被拦；改成 `bypass 4` 后房主自己放行 |
| 20 | **命令方块** | 创造模式放命令方块执行 `/give @a stone` | 默认被拦（`allowCommandBlocks=false`）；改成 true 后放行 |
| 21 | 控制台兜底 | 服务器控制台执行 `/commandcontrol status` | 正常可用（不受启用组限制），能读出状态并改配置 |

### 9.4 纯逻辑自检（不需要启动游戏）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build-offline.ps1
```

覆盖：模式匹配语义（前缀/`*`/`**`/命名空间/防止 `giver` 误命中）、四个组的放行矩阵、**真实默认值（从生成的 TOML 断言）**、三层共用的判定矩阵（玩家/开作弊/白名单/命令方块/控制台/各游戏模式）、
权限等级表（含 `/seed`、未知命令兜底）、1.0.1 的管辖模式矩阵（四种模式默认全管 + 未知模式名按受管辖处理）、
管理命令始终放行规则、默认值回归（组 1 / 绕过等级 5 / 模式切换保护开启）。当前 **138 项全部通过**。

---

## 10. 已知限制与未验证项

1. **modId 只能用下划线**：FML 的 `ModInfo` 用 `VALID_MODID = ^[a-z][a-z0-9_]{1,63}$` 校验 modId，
   不匹配会 `throw new InvalidModFileException(...)`（`ModInfo.<init>` 字节码 offset 50–129），
   所以 `command-control` 会**直接拒载**。本模组的处理：modId = `command_control`，
   显示名 = `Command Control`，产物名 = `command-control-<版本>.jar`。
   （`VALID_NAMESPACE = ^[a-z][a-z0-9_.-]{1,63}$` 才允许连字符，别把两者搞混。）
2. **Configured 的 `[modproperties]` 键名未验证**：Configured（MrCrayfish）只发布在 CurseForge，
   本机网络无法拉取其 1.21.1 jar 反查键名，因此 `neoforge.mods.toml` 里只给了**注释形式的占位**，
   没有写死任何键名。装好 Configured 后可在其界面/文档中确认键名再启用。
3. **Modrinth 上 slug 为 `configured` 的是另一个模组**（Fabric 专用），与本模组声明的
   modId `configured` 无关；依赖声明按 modId 匹配，不会误判。
4. **`/execute`、`/function` 这类包装命令**：组 1~3 会因其等级 2 而整体拦掉；
   但“某个模组命令内部再执行其它命令”无法被本模组检测。
5. **命令树探测是启发式的**：requirement 谓词若依赖真实执行者实体/维度（例如 `/publish` 还要求单人存档），
   探测会失败并回落到 `unknownCommandLevel`；原版命令始终以静态表为准，不受探测影响。
6. **COMMON 配置的语义**：专用服务器上，客户端手里的 `config/command-control-common.toml` 不参与判定
   （判定只用服务端那份）。因此专用服务器请用 `/commandcontrol`（OP）或直接改服务端配置文件；
   Cloth 界面在单机/局域网（客户端即主机）场景最顺手。
7. **未做游戏内实机联调**：本机沙箱无法启动 GUI 客户端，验证止步于
   “真实 1.21.1 jar 编译通过 + **76 项**逻辑自检 + NightConfig 元数据校验（含 MODID 一致性）+ 代码生成默认配置”。
   实机清单见 §9.3（1.0.1 新增第 17~21 条，专门覆盖全模式、F3+F4、开作弊、命令方块）。
8. **镜像可用性会变**：`repo.stellardrift.ca` 是第三方（StellarDrift）的 internal 仓库，长期可用性无保证；
   若哪天它失效，可换回官方 `maven.neoforged.net`（在能访问的网络下）或其它完整镜像，
   只需调整 `build.gradle` 的 `repositories` 块。
9. **1.0.1 的默认值是“有意收紧”的**：启用组默认 1、绕过等级默认 5、命令方块默认不放行。
   如果你更想要“装上先不管”的旧行为：`/commandcontrol preset NO_RESTRICTION`。
10. **模式切换保护有一个例外**：切到**旁观模式**一律放行（硬核死亡、服务器强制旁观属于降级，
    拦住会把玩家卡死）。所以旁观不能当作“封锁模式”使用，但旁观玩家本身仍受命令限制
    （`restrictSpectator` 默认开）。
11. **旧配置残留键**：1.0.0 写出的 `general.onlyInAdventureMode` 与 `permissions.allowNonPlayerSources`
    在 1.0.1 已移除；NightConfig 会忽略不认识的行（不影响启动），但建议手工删掉以免误解。
12. **`/commandcontrol` 是唯一始终放行的命令**：它的权限等级要求（默认 3）仍然生效，
    普通玩家敲不动；这样做的目的是避免严格组把服主自己锁死。

---

## 许可证与署名（License & Credits）

* 许可证：**MIT**，全文见 [LICENSE](LICENSE)。
* 所有者 / Owner：**lyhyy2007**
* 程序提供 / Program provider：**deepseek**
* 上游前置：[Cloth Config API](https://modrinth.com/mod/cloth-config)（15.0.140）、Configured（可选）

```text
MIT License

Copyright (c) 2026 lyhyy2007
```
