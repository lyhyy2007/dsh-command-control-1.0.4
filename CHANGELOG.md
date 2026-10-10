# 更新日志（Changelog）

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。署名：所有者 lyhyy2007，程序提供 deepseek。

## [1.0.4] - 2026-10-10

### 修复

* **「不禁用指令」模式下 `/tp`、`/time` 等命令从解析与 Tab 补全里彻底消失**（1.0.3 的严重问题）。
  命令树闸门此前不管当前预设都会包装根命令，现在改为**只在「禁用指令」预设下装配**：
  新增 `GuardState#shouldGateCommandTree()`（总开关 + 闸门开关 + 预设三者缺一不可）。
* 命令树手术抽成独立类 `CommandTreeGate`，新增**完整性校验**（根命令数量不变、每个名字都还在、包装节点确实生效）
  与**自动回滚**：任何异常或不一致都整体撤回，命令树恢复原样并记 ERROR。
* 包装后的 requirement 内 `try/catch`：闸门判定抛异常时只跑原 requirement，绝不让命令因本模组变成“不存在”。

### 变更

* **预设精简为两个**（原「仅传送」「传送+天气+时间」已删除）：
  * **禁用指令** `DISABLED`（**默认**）：只放行权限等级 0 的命令（`help/list/me/msg/tell/w/teammsg/tm/trigger`）+ `/commandcontrol`；
  * **不禁用指令** `NO_RESTRICTION`：命令树零改动，所有命令照常可见可用（`disabledCommands` 仍在执行前精确拦截）。
* 旧配置里残留的 `TELEPORT_ONLY` / `TELEPORT_WEATHER_TIME` 会被自动纠正为 `DISABLED`（日志说明）。

### 测试

* 新增命令树手术的**离线回归测试**：用 `CommandDispatcher<String>` 真实跑一遍包装流程，
  覆盖 `/teleport` + 别名 `/tp`（redirect）、`/give`、`/time`、`/help`、`/kill`（requires op）。
* 自检用例 121 → **138 项**，全部通过。

## [1.0.3] - 2026-10-10

### 修复

* **进入存档时游戏无响应（MC 卡死）**。根因：权限探测 `probe()` 去测试“已被本模组包装过”的
  requirement，而包装后的 requirement 又会回到闸门判定，形成
  `allowRoot → resolve → probe → requirement → allowRoot → …` **无限递归**；
  叠加 `ConcurrentHashMap.computeIfAbsent` 的映射函数重入，本该 `StackOverflowError` 的场景变成了线程挂起。
* 三道保险：① 探测只测**包装前**的原始 requirement；② 新增 `ReentrancyGuard`（ThreadLocal 深度）保证判定不可重入；
  ③ 弃用 `computeIfAbsent`，改 `get` + `putIfAbsent`。
* 新增逃生开关 `permissions.enableDispatcherGate` 与 `/commandcontrol gate <bool>`。

### 测试

* 新增 `ReentrancyGuard` 回归测试（含“再入必须走 fallback”），自检 112 → **121 项**。

## [1.0.2] - 2026-10-10

### 新增

* **命令树强制闸门**（第一层拦截）：包装根命令 requirement，判定不过的命令在解析期即被截断
  （不进入执行流程、Tab 补全里也不出现，命令方块 / 告示牌 / 函数同样被截断）。
* **配置版本迁移** `general.configVersion`：NightConfig 只补缺失键、不覆盖已有值，
  旧文件里的 `bypassPermissionLevel = 4` 会被沿用以至于“看起来没生效”，现在一次性迁移为 5。
* `/commandcontrol diag` 诊断命令：逐层报告闸门/执行层/模式层的安装与拦截计数，并实时算出示例命令的判定结果。
* `GuardState.current()` 自愈：配置已加载却仍是兜底快照时自动重建，不依赖事件时序。

### 变更

* 判定逻辑重构为纯函数（`SourceFacts` + `Verdict`），三层共用同一套结论。

## [1.0.1] - 2026-10-10

### 变更

* `preset` 默认值由「不禁用」改为「禁用指令」；`bypassPermissionLevel` 默认值由 4 改为 **5**（开作弊的玩家同样受限）。
* 删除“仅冒险模式生效”，改为四个游戏模式开关（`restrictSurvival` / `restrictCreative` / `restrictAdventure` / `restrictSpectator`），默认全开。
* 新增 `protectGameModeSwitch`（默认开）：拦截 F3+F4、`/gamemode` 以及其它模组的 `setGameMode`。
* 非玩家来源拆分为 `allowConsoleSources`（默认 true）与 `allowCommandBlocks`（默认 false）。
* 启动/重载/开服输出状态摘要；拦截命令与模式切换记 INFO。
* `/commandcontrol` 改为始终放行的管理命令（自身仍要求权限等级 3）。

## [1.0.0] - 2026-10-10

### 新增

* 首个版本：按启用组 + 自定义列表在服务端拦截/放行命令。
* 四层判定：白名单 → 游戏模式 → 绕过等级 → 启用组；支持参数级禁用/启用模式（`*`、`**` 通配）。
* 原版命令权限等级表（源码核对）+ 模组命令的命令树探测。
* `ModConfigSpec`（COMMON）配置 + Cloth Config 界面 + Configured 自动菜单。
* `/commandcontrol` 管理命令、`/cmdguard` 时代的四预设（后续在 1.0.4 精简为两个）。
