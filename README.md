# Command Control —— Minecraft 1.21.1 / NeoForge 命令控制

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green.svg)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.x-orange.svg)](https://neoforged.net/)
[![Version](https://img.shields.io/badge/version-1.0.4-blue.svg)](CHANGELOG.md)

自定义禁用或启用命令，在配置界面中顺手把 F3+F4 切换游戏模式关掉。
非专用服务器可通过 Cloth Config 配置。
在配置界面中顺手把 **F3+F4 切换游戏模式**关掉了。

* 所有者 / Owner：**lyhyy2007**　程序提供 / Program provider：**deepseek**　许可证：[MIT](LICENSE)
* 环境：Minecraft **1.21.1** + NeoForge **21.1.x**（任意 21.1 小版本）+ Java 21
* 已构建成品：`build/libs/command-control-1.0.4.jar`（拖进 `mods/` 即用）
* 可选前置：[Cloth Config](https://modrinth.com/mod/cloth-config)（游戏内图形配置界面）、
  Configured（自动为模组生成配置菜单）——都不装也能正常用

---

## English Quick Start

1. Drop `command-control-1.0.4.jar` into your **server's** `mods/` folder (and your client's `mods/` for
   singleplayer / LAN hosting — interception happens on the server side).
2. Launch once; a config file appears at `config/command-control-common.toml`.
3. The default preset is **`DISABLED`**: only permission-level-0 commands
   (`/help /list /me /msg /tell /w /teammsg /tm /trigger`) plus `/commandcontrol` are usable — **including for the
   world host with cheats enabled**.
4. To allow everything again: `/commandcontrol preset 2` (or `preset = "NO_RESTRICTION"` in the config).
5. Diagnose anytime with `/commandcontrol diag` — it reports every interception layer, its counters, and the live
   verdict for `/give`, `/tp`, `/time`, `/help`.
6. Full Chinese tutorial below; internals, crash post-mortems and API research live in
   [docs/TECHNICAL-NOTES.md](docs/TECHNICAL-NOTES.md).

---

## 目录

1. [它解决什么问题](#1-它解决什么问题)
2. [安装](#2-安装)
3. [30 秒确认「真的生效了」](#3-30-秒确认真的生效了)
4. [两个预设怎么选](#4-两个预设怎么选)
5. [实战配方](#5-实战配方)
6. [游戏内管理命令手册](#6-游戏内管理命令手册)
7. [配置文件详解](#7-配置文件详解)
8. [图形配置界面（Cloth Config / Configured）](#8-图形配置界面cloth-config--configured)
9. [权限等级与受管命令速查](#9-权限等级与受管命令速查)
10. [排错与常见问题](#10-排错与常见问题)
11. [兼容性说明](#11-兼容性说明)
12. [从源码构建（开发者）](#12-从源码构建开发者)
13. [许可证与署名](#13-许可证与署名)

---

## 1. 它解决什么问题

原版「允许作弊」是**全有或全无**：开了作弊，房主/OP 什么命令都能用；不开作弊，普通玩家连 `/help` 都受限。
而实际需求经常是中间态：

* 联机生存：想让大家用 `/tp`、`/time`、`/weather`，但**不许** `/give`、`/gamemode`、`/op`；
* 冒险地图：只允许 `/help`、`/list`、`/msg`，**其余一律封死**，而且不能靠开作弊绕过；
* 出租服 / 公开服：给管理员白名单开后门，其余人严格受限；
* 顺手把 **F3+F4 切创造**堵掉（那是网络包路径，普通命令拦截管不到——本模组专门处理了）。

Command Control 在**服务端**拦截，三层同时生效：

| 层 | 时机 | 作用 |
|---|---|---|
| ① 命令树闸门 | **解析期** | 判定不过的命令直接当不存在：不执行、**Tab 补全里也不出现**（仅在「禁用指令」预设下装配） |
| ② `CommandEvent` | 执行前 | 取消执行并给玩家提示；负责**参数级**规则（如 `tp @e`） |
| ③ 游戏模式切换拦截 | 改模式时 | 拦住 F3+F4、`/gamemode` 以及其它模组调用 `setGameMode` |

三层共用同一套判定，结论一致、不会互相打架。

---

## 2. 安装

### 2.1 放到哪里

| 你的玩法 | jar 放哪 |
|---|---|
| 单人 / 局域网联机（自己当主机） | **客户端** `mods/`（集成服务端与客户端同一个进程，装客户端即同时作用于服务端） |
| 专用服务器 | **服务端** `mods/`（玩家无需安装；拦截完全在服务端生效） |
| PCL2 / HMCL **版本隔离**实例 | `versions/<实例名>/mods/`（**注意不是全局 `.minecraft/mods`**） |
| 非隔离实例 | `.minecraft/mods/` |

> 配置文件与 `mods` 同级：普通实例在 `.minecraft/config/command-control-common.toml`，
> 版本隔离实例在 `versions/<实例名>/config/command-control-common.toml`。

### 2.2 版本要求

* Minecraft **1.21.1**，NeoForge **21.1.x**（构建基线 21.1.233，`versionRange=[21.1,)` 覆盖整条 21.1 线）；
* Java 21；
* 纯服务端可用——Cloth Config / Configured 只是「多一个图形界面」，不装不影响拦截。

### 2.3 首次启动会发生什么

1. 生成 `config/command-control-common.toml`（默认 `preset = "DISABLED"`，**装上就开始管**）；
2. 日志出现两行关键信息：

```
[command_control] 配置已加载：总开关=开，启用组=1.禁用指令，管辖模式=生存 / 创造 / 冒险 / 旁观，
                  模式切换保护=开，绕过等级=5（5=谁都不放行），放行控制台=true，放行命令方块=false，
                  闸门已装根命令=N 个，...
[command_control] 命令树强制闸门已安装：已包装 N 个根命令（第 1 次安装）
```

看到这两行就说明三层都装上了。

---

## 3. 30 秒确认「真的生效了」

游戏内敲（需要权限等级 3+；单人开作弊的房主就是等级 4）：

```
/commandcontrol status      ← 一行行读出当前生效状态
/commandcontrol diag        ← 逐层自证：三层是否装上、各拦了多少次、示例命令的实时判定
```

`/commandcontrol diag` 输出示例：

```
—— Command Control 诊断（v1.0.4，所有者 lyhyy2007，程序提供 deepseek）——
配置文件：config/command-control-common.toml　configVersion=1（当前 1）
快照状态：ready=true　总开关=开　启用组=1.禁用指令　管辖模式=生存 / 创造 / 冒险 / 旁观　绕过等级=5
① 命令树强制闸门：开关=开，已包装根命令 218 个，安装 1 次，拦截 0 次（give=true，tp=true）
② CommandEvent 执行前取消：已注册，拦截 0 次
③ 游戏模式切换拦截：保护开关=开，拦截 0 次
当前命令源：玩家 Steve（等级 4）（玩家=true，模式受管辖=true，白名单=false，达到绕过等级=false）
　/give（等级 2）→ 拦截：启用组 1（禁用指令）不允许 /give（其权限等级为 2）
　/gamemode（等级 2）→ 拦截：...
　/tp（等级 2）→ 拦截：...
　/help（等级 0）→ 放行：启用组 1
```

再实测一下：以**开着作弊的房主**身份敲 `/give @s stone` → 应当被拦并提示；敲 `/help` → 正常。

---

## 4. 两个预设怎么选

| 编号 | 名称 | 允许的权限等级 | 命令树闸门 | 直观效果 |
|---|---|---|---|---|
| **1** | **禁用指令** `DISABLED`（**默认**） | 仅 **0** | **安装** | 只放行 `/help /list /me /msg /tell /w /teammsg /tm /trigger` + `/commandcontrol`；其余命令**执行前被截断，补全里也看不到** |
| 2 | **不禁用指令** `NO_RESTRICTION` | **0–4 全部** | **不安装** | 所有命令照常可见可用；`disabledCommands` 里的命令仍在执行前被精确拦下 |

切换方式（任选其一，立即生效并写回文件）：

```
/commandcontrol preset 1              # 或 preset disabled / preset 禁用指令
/commandcontrol preset 2              # 或 preset no_restriction
```

> **怎么选**：想「默认什么都不许，只开后门」→ 用 1；想「默认都许，只点名禁几条」→ 用 2。

---

## 5. 实战配方

> 名单里模式的写法都支持参数：`give` 命中所有 `/give ...`；
> `give @a diamond` 只命中前三段一致的；`tp * 0 0` 里 `*` 是任意一个词元；
> `say hello **` 里 `**` 只能放末尾，代表其后任意个词元。忽略大小写，`minecraft:give` 这类命名空间自动兼容。

### 配方 A：联机生存服——只许传送/时间/天气，禁 give、gamemode、op

```toml
[general]
	preset = "NO_RESTRICTION"        # 先全部放行，再点名禁
[lists]
	disabledCommands = ["give", "gamemode", "op", "deop", "kill", "ban", "kick", "whitelist", "stop"]
```

### 配方 B：冒险地图——只放行聊天类命令，其余封死（含房主）

```toml
[general]
	preset = "DISABLED"              # 默认值：只放行等级 0 的命令
[permissions]
	bypassPermissionLevel = 5        # 默认值：开作弊的房主也一起受限
[lists]
	enabledCommands = ["me", "msg **"]   # 想额外放开哪条就写哪条
```

### 配方 C：允许传送，但**不许**把实体传送走（防止把别人扔进虚空）

```toml
[general]
	preset = "NO_RESTRICTION"
[lists]
	disabledCommands = ["tp @e", "tp @a", "teleport @e", "teleport @a"]
```

`/tp @e 0 0 0` 会被拦，`/tp 100 64 100`（把自己传送）照常可用。

### 配方 D：严格模式下只额外放开两条命令

```toml
[general]
	preset = "DISABLED"
[lists]
	enabledCommands = ["time set day", "weather clear"]
```

启用列表只「加」不「减」：`/time set night` 依然被拦。

### 配方 E：给管理员/主播开后门（白名单）

```
/commandcontrol whitelist add Steve
/commandcontrol whitelist add 069a79f4-44e9-4726-a5be-fca90e38aaf5   # 也可以填 UUID
/commandcontrol whitelist list
```

白名单玩家**绕过全部限制**（含预设与模式切换限制）。

### 配方 F：服主也不想给自己开后门 / 或相反

```toml
[permissions]
	bypassPermissionLevel = 5    # 默认：谁都不放行（开作弊的房主等级是 4，照样受限）
	# bypassPermissionLevel = 4  # 改成 4：OP / 联机主机 / 控制台放行，只限制普通玩家
```

### 配方 G：禁止 F3+F4 切换游戏模式

```toml
[general]
	protectGameModeSwitch = true     # 默认已开：被管辖的玩家都改不了模式
```

游戏内快速开关：`/commandcontrol gamemodeguard false`（想还原时再 `true`）。
例外：**切到旁观模式一律放行**（硬核死亡、服务器强制旁观属于降级，拦住会把玩家卡死）。

### 配方 H：只管生存/冒险，不管创造（给建筑服留出创造模式）

```toml
[general]
	restrictSurvival = true
	restrictCreative = false         # 创造模式玩家不受命令限制
	restrictAdventure = true
	restrictSpectator = true
```

游戏内：`/commandcontrol restrict creative false`，也可以用 `all` 一次全开/全关。

### 配方 I：命令方块机器 / 自动化要照常跑

```toml
[permissions]
	allowCommandBlocks = true        # 默认 false：创造玩家不能靠命令方块绕过限制
	allowConsoleSources = true       # 默认 true：服务器控制台 / RCON 兜底入口
```

### 配方 J：自定义拦截提示

```toml
[messages]
	deniedMessage = "这条命令被服务器禁用了：{command}（当前预设 {preset}）"
	gameModeDeniedMessage = "你不能切换游戏模式"
```

占位符：`{command}` 完整命令、`{root}` 命令名、`{preset}` 当前预设。

### 配方 K：临时整体关闭 / 只关掉「解析期截断」

```
/commandcontrol toggle false        # 整个模组不干预（等价于不装）
/commandcontrol gate false          # 命令仍在补全里可见，但执行前仍会被拦
/reload                             # 让 gate 的开关重新装到命令表上
```

### 配方 L：只拦模组命令（例如某模组的 `/home`）

```toml
[general]
	preset = "NO_RESTRICTION"
[lists]
	disabledCommands = ["home", "sethome", "ftbteams party create"]
[permissions]
	unknownCommandLevel = 2          # 未收录命令的兜底等级
	useDispatcherProbe = true        # 默认：用命令树探测模组命令的真实权限等级
```

---

## 6. 游戏内管理命令手册

`/commandcontrol` 需要权限等级 **3** 以上（管理员 / OP / 开作弊的房主）。
它**不受预设限制**（否则严格预设会把服主自己锁死），但它的权限等级要求照旧生效。
所有修改都会写回 `config/command-control-common.toml` 并**立即生效**。

| 命令 | 作用 |
|---|---|
| `/commandcontrol` 或 `status` | 查看当前状态（含署名、版本、管辖模式、绕过等级、各层计数） |
| `/commandcontrol diag` | 逐层诊断 + 对 `/give`、`/gamemode`、`/tp`、`/weather`、`/time`、`/help`、`/list` 实时算出放行/拦截与原因 |
| `/commandcontrol preset <1-2\|名称>` | 切换预设：`1`/`disabled`、`2`/`no_restriction`，也接受中文显示名 |
| `/commandcontrol toggle <true\|false>` | 总开关 |
| `/commandcontrol gamemodeguard <true\|false>` | 是否禁止 F3+F4 等模式切换 |
| `/commandcontrol gate <true\|false>` | 第一层「命令树闸门」开关（改完 `/reload` 生效） |
| `/commandcontrol bypass <0-5>` | 绕过所需权限等级；`5`=谁都不放行（默认），`4`=OP/主机放行 |
| `/commandcontrol restrict <survival\|creative\|adventure\|spectator\|all> <true\|false>` | 管辖哪些游戏模式 |
| `/commandcontrol allow add\|remove\|list [模式]` | 自定义**启用**列表（优先级最高，只加不减） |
| `/commandcontrol deny add\|remove\|list [模式]` | 自定义**禁用**列表（支持参数级模式） |
| `/commandcontrol whitelist add\|remove\|list [玩家]` | 白名单（用户名或 UUID），绕过全部限制 |
| `/commandcontrol info` | 两个预设对照 + 原版命令权限等级统计 |

示例：

```
/commandcontrol preset 2
/commandcontrol deny add give
/commandcontrol deny add tp @e
/commandcontrol allow add time set day
/commandcontrol whitelist add Steve
/commandcontrol restrict all true
/commandcontrol bypass 5
/commandcontrol diag
```

---

## 7. 配置文件详解

路径：`config/command-control-common.toml`（首次启动自动生成；
完整默认样例见 [docs/default-config-example.toml](docs/default-config-example.toml)）。
**支持热重载**：游戏运行时改文件保存，日志会打印「配置已重载」，无需重启。

### `[general]`

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `enabled` | bool | `true` | 总开关；`false` = 完全不干预 |
| `preset` | enum | `DISABLED` | `DISABLED`=禁用指令（只放行等级 0）/ `NO_RESTRICTION`=不禁用指令 |
| `restrictSurvival` | bool | `true` | 管辖生存模式 |
| `restrictCreative` | bool | `true` | 管辖创造模式（**开作弊的房主就在这一档**） |
| `restrictAdventure` | bool | `true` | 管辖冒险模式 |
| `restrictSpectator` | bool | `true` | 管辖旁观模式 |
| `protectGameModeSwitch` | bool | `true` | 禁止被管辖的玩家切换游戏模式（F3+F4 / `/gamemode` / 其它模组） |
| `configVersion` | int | `0` | 配置结构版本，**模组自动维护，勿手工改小**（见 §10.3） |

### `[lists]`

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `disabledCommands` | 字符串列表 | `[]` | 命中即拦（白名单玩家与 `bypassPermissionLevel` 仍优先） |
| `enabledCommands` | 字符串列表 | `[]` | 命中即放行，优先级高于禁用列表与预设 |
| `whitelistPlayers` | 字符串列表 | `[]` | 白名单玩家（用户名或 UUID），绕过全部限制 |

### `[permissions]`

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `bypassPermissionLevel` | int 0–5 | `5` | 权限等级 ≥ 此值直接放行。**5 = 谁都不放行**（开作弊的玩家等级是 4，照样受限）；`4` = OP/主机放行；`0` = 连控制台都受限 |
| `unknownCommandLevel` | int 0–5 | `2` | 未收录命令（含模组命令）的兜底等级 |
| `allowConsoleSources` | bool | `true` | 放行服务器控制台 / RCON（服主兜底入口，建议保持 true） |
| `allowCommandBlocks` | bool | `false` | 放行命令方块 / 命令方块矿车；默认关闭，创造玩家不能靠它绕过 |
| `useDispatcherProbe` | bool | `true` | 用命令树探测模组命令的真实权限等级 |
| `enableDispatcherGate` | bool | `true` | 第一层：命令树强制闸门（解析期截断）。出兼容问题可关掉，仅保留执行层 |

### `[messages]`

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `deniedMessage` | string | `该命令已被 Command Control 禁用：{command}（当前启用组：{preset}）` | 命令被拦时的提示；占位符 `{command}` `{root}` `{preset}` |
| `gameModeDeniedMessage` | string | `游戏模式切换已被 Command Control 禁用` | 模式切换被拦时的提示 |

### 一份「联机生存服」完整示例

```toml
[general]
	enabled = true
	preset = "NO_RESTRICTION"
	restrictSurvival = true
	restrictCreative = true
	restrictAdventure = true
	restrictSpectator = true
	protectGameModeSwitch = true

[lists]
	disabledCommands = ["give", "gamemode", "op", "deop", "ban", "kick", "stop", "whitelist", "tp @e"]
	enabledCommands = []
	whitelistPlayers = ["Steve"]

[permissions]
	bypassPermissionLevel = 5
	unknownCommandLevel = 2
	allowConsoleSources = true
	allowCommandBlocks = false
	useDispatcherProbe = true
	enableDispatcherGate = true

[messages]
	deniedMessage = "该命令已被 Command Control 禁用：{command}（当前启用组：{preset}）"
	gameModeDeniedMessage = "游戏模式切换已被 Command Control 禁用"
```

---

## 8. 图形配置界面（Cloth Config / Configured）

* **装了 Configured**：它读取本模组使用的 NeoForge 配置系统（`ModConfigSpec`），
  会**自动**生成游戏内菜单，本模组不需要额外代码。
* **装了 Cloth Config**：模组列表里本模组条目会出现「配置」按钮，界面分
  `常规` / `管辖范围` / `自定义列表` / `权限与判定` / `提示语`；保存即写回同一份 TOML。
* 两者都只是**编辑同一份配置**的不同入口，不存在两套数据。
* 专用服务器上请优先用 `/commandcontrol` 或直接改服务端配置文件——客户端手里那份 TOML 不参与判定。

---

## 9. 权限等级与受管命令速查

原版 `Commands` 常量（1.21.1 实测）：

| 等级 | 含义 | 典型命令 |
|---|---|---|
| **0** | 所有玩家 | `help` `list` `me` `msg` `tell` `w` `teammsg` `tm` `trigger` |
| 1 | Moderator | **1.21.1 已无命令使用** |
| **2** | Gamemaster | `give` `tp` `teleport` `weather` `time` `gamemode` `effect` `summon` `execute` `setblock` `scoreboard` `spreadplayers` … |
| **3** | Admin | `ban` `ban-ip` `banlist` `deop` `kick` `op` `pardon` `whitelist` `tick` `transfer` … |
| **4** | Owner | `stop` `save-all` `save-off` `save-on` `publish` `perf` `jfr` |

* 完整等级表（1.21.1 全量、逐条）见 [docs/TECHNICAL-NOTES.md](docs/TECHNICAL-NOTES.md) 的「原版命令权限等级全表」；
  游戏内也可 `/commandcontrol info` 看统计。
* **`/seed` 特例**：集成服务器（单人/局域网）对所有人开放，专用服务器需要等级 2。
* **模组命令**：不在表里时，`useDispatcherProbe = true` 会用命令树探测其真实等级
  （伪造不同权限等级的假命令源去测根节点的 requirement，取最小可用等级并缓存）；
  探测失败则用 `unknownCommandLevel`。
* **权限等级怎么来的**：命令源自身的等级——单人/局域网开作弊的玩家是 **4**，普通玩家 **0**，
  服务器 OP 按 `ops.json`，控制台/RCON 是 **4**。

---

## 10. 兼容性说明

* **拦截点**：服务端。玩家端装不装都能被限制（图形配置界面需要客户端装 Cloth Config / Configured）。
* **覆盖的入口**：玩家聊天栏命令、控制台、RCON、命令方块、命令方块矿车、告示牌命令、`/function` 函数、
  `/execute ... run <命令>`（内层命令同样按同一套规则判定）。
* **F3+F4**：走网络包 → `ServerPlayer#setGameMode`，不经过命令系统，由第三层单独拦截。
* **不改动原版代码**：只在 `RegisterCommandsEvent` 时包装根命令的 requirement（且仅在「禁用指令」预设下），
  并监听 `CommandEvent` 与 `PlayerChangeGameModeEvent`。
* **多世界 / 多维度**：判定只看玩家与预设，不区分维度。
* **与权限插件共存**（LuckPerms 等）：本模组按「命令源权限等级」判定，权限插件调整玩家等级后会自动反映；
  需要放行时用 `bypassPermissionLevel` 或白名单。

---

## 11. 从源码构建（开发者）

```bat
:: Windows
gradlew.bat build           :: 产物 build\libs\command-control-1.0.4.jar
gradlew.bat clean build     :: 从零构建（已验证可跑通）
gradlew.bat runClient       :: 开发环境起客户端
gradlew.bat runServer       :: 开发环境起服务端（首次需把 run\eula.txt 改成 eula=true）
gradlew.bat dist            :: 额外把产物复制一份到 dist\
```

* 构建需要能访问 Maven 仓库；官方 `maven.neoforged.net` 在部分网络下不可达，
  本工程已内置可用镜像组合（BMCLAPI + StellarDrift + MinecraftForge + Mojang libraries）
  与本地补丁仓 `local-maven/`（补 `net.neoforged:minecraft-dependencies`，由
  [`tools/gen-minecraft-dependencies.ps1`](tools/gen-minecraft-dependencies.ps1) 从 Mojang 版本清单重建）。
  细节与排查过程见 [docs/TECHNICAL-NOTES.md](docs/TECHNICAL-NOTES.md)。
* 不依赖 Gradle 的备用路线：

```powershell
# 需要本机装过 NeoForge 1.21.1 + Cloth Config jar（用 -ClothJar 指定）
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build-offline.ps1
```

  它会 javac 编译 → 跑 **138 项离线自检** → 校验 `neoforge.mods.toml` → 生成默认配置样例 → 打包。

* 源码结构：

```
src/main/java/com/lyhyy2007/commandcontrol/
├── CommandControl.java          # @Mod 入口：注册配置、显式注册三层监听、配置迁移、启动日志
├── CommandGuard.java            # 第二/三层：CommandEvent 取消 + 游戏模式切换拦截
├── CommandGate.java             # 第一层：命令树闸门（Minecraft 侧判定与来源分类）
├── CommandTreeGate.java         # 第一层的手术实现（泛型、可离线测试、失败自动回滚）
├── GuardState.java              # 不可变配置快照 + 全部判定逻辑（纯函数，可离线测试）
├── GuardPreset.java             # 两个预设
├── CommandPermissions.java      # 原版权限等级表 + 模组命令探测
├── CommandPattern.java          # 命令匹配（前缀 + * / ** 通配）
├── ReentrancyGuard.java         # 判定不可重入（防止递归卡死）
├── command/CommandControlCommand.java   # /commandcontrol 管理命令
├── config/CommandControlConfig.java     # ModConfigSpec 定义
└── client/…                     # Cloth Config 界面 + 客户端注册（仅客户端加载）
```

---

## 12. 许可证与署名

* 许可证：**MIT**，全文见 [LICENSE](LICENSE)。
* 所有者 / Owner：**lyhyy2007**
* 程序提供 / Program provider：**deepseek**
* 上游前置：[Cloth Config API](https://modrinth.com/mod/cloth-config)（15.0.140，可选）、Configured（可选）

```text
MIT License

Copyright (c) 2026 lyhyy2007
```

更多资料：

* [CHANGELOG.md](CHANGELOG.md) —— 1.0.0 → 1.0.4 全部更新与两次事故的修复记录
* [docs/TECHNICAL-NOTES.md](docs/TECHNICAL-NOTES.md) —— 技术说明：API 核对结论、三层实现细节、
  命令树手术与回滚、镜像方案、原版命令权限等级全表、验证清单
* [docs/default-config-example.toml](docs/default-config-example.toml) —— 由代码生成的默认配置样例
