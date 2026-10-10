<#
.SYNOPSIS
  Command Control 离线构建脚本：在无法访问 maven.neoforged.net（因此 ModDevGradle 的
  NeoForm 管线跑不起来）的机器上，用本机已安装的 NeoForge 1.21.1 运行库直接编译并打包。

.DESCRIPTION
  前提：
    1) 本机装过 NeoForge 1.21.1（PCL/HMCL/官方启动器任意一种），libraries 目录齐全；
    2) 有一份“映射后的 Minecraft 类”jar。优先级：
       - 参数 -McClassesJar 指定的 jar
       - Gradle/NeoForm 缓存里的 compiledWithNeoForge_*.jar（本机跑过 NeoForge 开发环境时有）
    3) Cloth Config 的 jar（编译客户端配置界面用）。默认在项目根目录或 -ClothJar 指定。

  它会：编译 src/main/java → 运行 tools/selftest/SelfTest.java → 打包
  build/libs/Command Control-<版本>-offline.jar（含展开占位符后的 neoforge.mods.toml）。

.EXAMPLE
  pwsh -File tools/build-offline.ps1
  pwsh -File tools/build-offline.ps1 -McHome "D:\mc\.minecraft" -NeoForgeVersion 21.1.249
#>
param(
    [string]$McHome = "C:\LxqAppData\Minecraft CE\.minecraft",
    [string]$McClassesJar = "",
    [string]$NeoForgeVersion = "21.1.233",
    [string]$ClothJar = "",
    [string]$LoaderVersion = "4.0.45",
    [switch]$SkipSelfTest
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$libraries = Join-Path $McHome "libraries"
$buildDir = Join-Path $projectRoot "build"
$classesDir = Join-Path $buildDir "offline-classes"
$testDir = Join-Path $buildDir "offline-selftest"
$javac = Join-Path $env:JAVA_HOME "bin\javac.exe"
$java = Join-Path $env:JAVA_HOME "bin\java.exe"
$jarTool = Join-Path $env:JAVA_HOME "bin\jar.exe"

Write-Host "== Command Control 离线构建 ==" -ForegroundColor Cyan
if (-not (Test-Path $javac)) { throw "找不到 javac：$javac（请设置 JAVA_HOME 指向 JDK 21）" }
if (-not (Test-Path $libraries)) { throw "找不到 $libraries（用 -McHome 指定 .minecraft 目录）" }

# ---------------------------------------------------------------- 类路径组装
# 只取 1.21.1 线用得到的库：避免其它版本（26.x / Forge 老版本）的 jar 覆盖同名类。
$includeDirs = @(
    "net\neoforged\neoforge\$NeoForgeVersion",
    "net\neoforged\fancymodloader\loader\$LoaderVersion",
    "net\neoforged\fancymodloader\earlydisplay\$LoaderVersion",
    "net\neoforged\bus",
    "net\neoforged\JarJarFileSystems",
    "net\neoforged\JarJarMetadata",
    "net\neoforged\JarJarSelector",
    "net\neoforged\coremods",
    "net\neoforged\accesstransformers",
    "com\mojang\brigadier",
    "com\mojang\datafixerupper",
    "com\mojang\authlib",
    "com\mojang\logging",
    "com\mojang\blocklist",
    "com\mojang\math",
    "com\google\guava",
    "com\google\code\gson",
    "com\electronwill\night-config",
    "com\google\code\findbugs",
    "com\google\errorprone",
    "org\slf4j",
    "org\apache\logging\log4j",
    "org\apache\commons\commons-lang3",
    "org\apache\commons\commons-io",
    "org\jetbrains\annotations",
    "org\joml\joml",
    "org\checkerframework",
    "it\unimi\dsi\fastutil",
    "net\minecraftforge\eventbus",
    "cpw\mods\modlauncher",
    # ModConfigSpec 静态初始化会建 Log4j logger，其类加载器上下文选择器需要 securejarhandler
    "cpw\mods\securejarhandler\2.1.10",
    # net.neoforged.api.distmarker.Dist 由 mergetool 的 api jar 提供；放在最后，
    # 避免它的其它类覆盖 fancymodloader 的 FML 实现。
    "net\neoforged\mergetool\2.0.0"
)

$jars = New-Object System.Collections.Generic.List[string]

# 1) 映射后的 Minecraft 类（必需）
if (-not $McClassesJar) {
    $candidates = @()
    $nfrtCache = Join-Path $env:USERPROFILE ".gradle\caches\neoformruntime\intermediate_results"
    if (Test-Path $nfrtCache) {
        $candidates += (Get-ChildItem -File $nfrtCache -Filter "compiledWithNeoForge_*_output.jar" -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName)
    }
    $candidates += (Join-Path $buildDir "mc-classes.jar")
    $McClassesJar = ($candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1)
}
if (-not $McClassesJar -or -not (Test-Path $McClassesJar)) {
    throw @"
找不到“映射后的 Minecraft 类”jar。请任选一种方式：
  * 用 -McClassesJar 指定（例如从开发环境缓存里导出）；
  * 先把工程用 Gradle 正常构建一次（需要能访问 maven.neoforged.net），
    NeoForm 会把结果写进 %USERPROFILE%\.gradle\caches\neoformruntime\intermediate_results；
  * 或把已有的 compiledWithNeoForge_*_output.jar 复制到 build\mc-classes.jar。
"@
}
$jars.Add($McClassesJar)
Write-Host "Minecraft 类：$McClassesJar"

# 2) Cloth Config（客户端配置界面）
if (-not $ClothJar) {
    $clothCandidates = @(
        (Join-Path $projectRoot "cloth-config-neoforge-15.0.140.jar"),
        (Join-Path $projectRoot "libs\cloth-config-neoforge-15.0.140.jar")
    )
    $ClothJar = ($clothCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1)
}
if (-not $ClothJar -or -not (Test-Path $ClothJar)) {
    throw "缺少 Cloth Config jar。请从 https://maven.shedaniel.me/me/shedaniel/cloth/cloth-config-neoforge/15.0.140/ 下载，放到项目根目录或用 -ClothJar 指定。"
}
$jars.Add($ClothJar)
Write-Host "Cloth Config：$ClothJar"

# 3) 运行库
foreach ($dir in $includeDirs) {
    $full = Join-Path $libraries $dir
    if (-not (Test-Path $full)) { continue }
    Get-ChildItem -Recurse -File $full -Filter *.jar -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*-sources.jar" -and $_.Name -notlike "*-javadoc.jar" } |
        ForEach-Object { $jars.Add($_.FullName) }
}
Write-Host ("类路径 jar 数量：" + $jars.Count)

$classpath = (($jars | Select-Object -Unique) -join ";")
# 关键：javac / java 的 @argfile 会把反斜杠当转义字符，写进参数文件前统一换成正斜杠。
$classpathArg = $classpath.Replace('\', '/')

# ---------------------------------------------------------------- 编译
Remove-Item -Recurse -Force $classesDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $classesDir | Out-Null
$sources = Get-ChildItem -Recurse -File (Join-Path $projectRoot "src\main\java") -Filter *.java |
    ForEach-Object { $_.FullName.Replace('\', '/') }
$argFile = Join-Path $buildDir "offline-javac.args"
New-Item -ItemType Directory -Force $buildDir | Out-Null
Set-Content -Path $argFile -Encoding ASCII -Value (@(
        "-encoding", "UTF-8", "-proc:none", "-Xlint:deprecation", "-nowarn",
        "-d", $classesDir.Replace('\', '/'),
        "-cp", "`"$classpathArg`""
    ) + $sources)
& $javac "@$($argFile.Replace('\','/'))"
if ($LASTEXITCODE -ne 0) { throw "编译失败（javac 退出码 $LASTEXITCODE）" }
$classCount = (Get-ChildItem -Recurse -File $classesDir -Filter *.class | Measure-Object).Count
Write-Host ("编译完成：" + $classCount + " 个 class") -ForegroundColor Green

# ---------------------------------------------------------------- 自检
if (-not $SkipSelfTest) {
    Remove-Item -Recurse -Force $testDir -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $testDir | Out-Null
    $testArg = Join-Path $buildDir "offline-selftest-javac.args"
    Set-Content -Path $testArg -Encoding ASCII -Value @(
        "-encoding", "UTF-8", "-proc:none",
        "-d", $testDir.Replace('\', '/'),
        "-cp", "`"$($classesDir.Replace('\','/'));$classpathArg`"",
        (Join-Path $projectRoot "tools\selftest\SelfTest.java").Replace('\', '/')
    )
    & $javac "@$($testArg.Replace('\','/'))"
    if ($LASTEXITCODE -ne 0) { throw "自检编译失败" }
    $runArg = Join-Path $buildDir "offline-selftest-run.args"
    Set-Content -Path $runArg -Encoding ASCII -Value @(
        "-cp", "`"$($testDir.Replace('\','/'));$($classesDir.Replace('\','/'));$classpathArg`"",
        "SelfTest"
    )
    & $java "@$($runArg.Replace('\','/'))"
    if ($LASTEXITCODE -ne 0) { throw "自检失败（退出码 $LASTEXITCODE）" }
    Write-Host "自检通过" -ForegroundColor Green
}

# ---------------------------------------------------------------- 打包
$props = @{}
# 必须显式 -Encoding UTF8：Windows PowerShell 5.1 默认按 ANSI 读，中文注释的字节
# 会吞掉行尾换行，导致紧跟其后的键（例如 mod_id）整行丢失。
[System.IO.File]::ReadAllLines((Join-Path $projectRoot "gradle.properties"), [System.Text.Encoding]::UTF8) | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z0-9_.]+)\s*=\s*(.*)$') { $props[$Matches[1]] = $Matches[2].Trim() }
}
if (-not $props["mod_id"]) { throw "gradle.properties 解析失败：读不到 mod_id（检查编码）" }
$modId = $props["mod_archive_name"]; if (-not $modId) { $modId = $props["mod_id"] }
$modVersion = $props["mod_version"]

$stage = Join-Path $buildDir "offline-stage"
Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item -Recurse -Force (Join-Path $classesDir "*") $stage

$resourceRoot = Join-Path $projectRoot "src\main\resources"
Get-ChildItem -Recurse -File $resourceRoot | ForEach-Object {
    $relative = $_.FullName.Substring($resourceRoot.Length + 1)
    $target = Join-Path $stage $relative
    New-Item -ItemType Directory -Force (Split-Path -Parent $target) | Out-Null
    # 和 build.gradle 的 processResources 行为一致：只对 neoforge.mods.toml 展开 ${...}（UTF-8 无 BOM）
    if ($relative -eq "META-INF\neoforge.mods.toml") {
        # Windows PowerShell 5.1 默认按 ANSI 读无 BOM 的 UTF-8 文件，中文注释会变成乱码
        # 甚至吞掉换行，所以这里必须显式 -Encoding UTF8。
        $text = Get-Content $_.FullName -Raw -Encoding UTF8
        $text = [regex]::Replace($text, '\$\{([A-Za-z0-9_.]+)\}', { param($m) $props[$m.Groups[1].Value] })
        [System.IO.File]::WriteAllText($target, $text, (New-Object System.Text.UTF8Encoding($false)))
    } else {
        Copy-Item -Force $_.FullName $target
    }
}

# ---------------------------------------------------------------- 元数据校验
$toml = Join-Path $stage "META-INF\neoforge.mods.toml"
$tomlCheckArgs = Join-Path $buildDir "offline-tomlcheck.args"
Set-Content -Path $tomlCheckArgs -Encoding ASCII -Value @(
    "-encoding", "UTF-8", "-proc:none",
    "-d", $testDir.Replace('\', '/'),
    "-cp", "`"$($testDir.Replace('\','/'));$($classesDir.Replace('\','/'));$classpathArg`"",
    (Join-Path $projectRoot "tools\selftest\TomlCheck.java").Replace('\', '/')
)
& $javac "@$($tomlCheckArgs.Replace('\','/'))"
if ($LASTEXITCODE -ne 0) { throw "TomlCheck 编译失败" }
$tomlRunArgs = Join-Path $buildDir "offline-tomlcheck-run.args"
Set-Content -Path $tomlRunArgs -Encoding ASCII -Value @(
    "-cp", "`"$($testDir.Replace('\','/'));$($classesDir.Replace('\','/'));$classpathArg`"",
    "TomlCheck", $toml.Replace('\', '/')
)
& $java "@$($tomlRunArgs.Replace('\','/'))"
if ($LASTEXITCODE -ne 0) { throw "mods.toml 校验失败" }

# ------------------------------------------------- 生成默认配置样例（文档用）
$genArgs = Join-Path $buildDir "offline-genconfig.args"
Set-Content -Path $genArgs -Encoding ASCII -Value @(
    "-encoding", "UTF-8", "-proc:none",
    "-d", $testDir.Replace('\', '/'),
    "-cp", "`"$($testDir.Replace('\','/'));$($classesDir.Replace('\','/'));$classpathArg`"",
    (Join-Path $projectRoot "tools\selftest\GenerateDefaultConfig.java").Replace('\', '/')
)
& $javac "@$($genArgs.Replace('\','/'))"
if ($LASTEXITCODE -ne 0) { throw "GenerateDefaultConfig 编译失败" }
$genRunArgs = Join-Path $buildDir "offline-genconfig-run.args"
Set-Content -Path $genRunArgs -Encoding ASCII -Value @(
    "-cp", "`"$($testDir.Replace('\','/'));$($classesDir.Replace('\','/'));$classpathArg`"",
    "GenerateDefaultConfig",
    (Join-Path $projectRoot "docs\default-config-example.toml").Replace('\', '/')
)
& $java "@$($genRunArgs.Replace('\','/'))"
if ($LASTEXITCODE -ne 0) { throw "生成默认配置样例失败" }

$outJar = Join-Path $buildDir "libs\$modId-$modVersion-offline.jar"
New-Item -ItemType Directory -Force (Split-Path -Parent $outJar) | Out-Null
Remove-Item -Force $outJar -ErrorAction SilentlyContinue
& $jarTool --create --file $outJar -C $stage .
if ($LASTEXITCODE -ne 0) { throw "打包失败" }
Write-Host ("产物：" + $outJar) -ForegroundColor Green
