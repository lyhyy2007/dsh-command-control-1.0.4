import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.FileConfig;
import java.nio.file.Path;
import java.util.List;

/**
 * 校验打包出来的 {@code META-INF/neoforge.mods.toml}：
 * 用和 FML 同款的 NightConfig TOML 解析器读一遍，并检查关键字段。
 * 1.21.1 上漏写 modLoader / loaderVersion 会直接导致客户端拒载（Missing ModLoader），
 * 所以这里做一次自动检查。
 */
public final class TomlCheck {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("用法: TomlCheck <neoforge.mods.toml>");
            System.exit(2);
        }
        Path path = Path.of(args[0]);
        int failures = 0;
        try (FileConfig config = FileConfig.of(path)) {
            config.load();

            String modLoader = config.get("modLoader");
            String loaderVersion = config.get("loaderVersion");
            String license = config.get("license");
            System.out.println("modLoader     = " + modLoader);
            System.out.println("loaderVersion = " + loaderVersion);
            System.out.println("license       = " + license);
            failures += expect("modLoader", "javafml", modLoader);
            failures += expectNotBlank("loaderVersion", loaderVersion);
            failures += expectNotBlank("license", license);

            List<Config> mods = config.get("mods");
            if (mods == null || mods.isEmpty()) {
                System.out.println("FAIL 缺少 [[mods]] 段");
                failures++;
            } else {
                Config mod = mods.get(0);
                String modId = mod.get("modId");
                String version = mod.get("version");
                String displayName = mod.get("displayName");
                System.out.println("mods[0]       = modId=" + modId + " version=" + version + " displayName=" + displayName);
                failures += expect("mods[0].modId", "command_control", modId);
                // Java 侧 @Mod 常量必须与 toml 一致（不一致 FML 会在加载期直接报错）
                try {
                    failures += expect("CommandControl.MODID 与 toml 一致",
                            com.lyhyy2007.commandcontrol.CommandControl.MODID, modId);
                } catch (Throwable t) {
                    System.out.println("FAIL 读取 CommandControl.MODID 失败：" + t);
                    failures++;
                }
                failures += expectNotBlank("mods[0].version", version);
                failures += expectNotBlank("mods[0].displayName", displayName);

                Config dependencies = config.get("dependencies");
                if (dependencies == null) {
                    System.out.println("FAIL 缺少 [[dependencies.<modId>]] 段");
                    failures++;
                } else {
                    List<Config> deps = dependencies.get("command_control");
                    if (deps == null || deps.isEmpty()) {
                        System.out.println("FAIL 缺少 [[dependencies.command_control]]（表头必须带 .modId）");
                        failures++;
                    } else {
                        for (Config dep : deps) {
                            System.out.println("dependency    = " + dep.get("modId")
                                    + " type=" + dep.get("type")
                                    + " range=" + dep.get("versionRange"));
                        }
                        failures += expectPresent(deps, "neoforge", "required");
                        failures += expectPresent(deps, "minecraft", "required");
                        failures += expectPresent(deps, "cloth_config", "optional");
                        failures += expectPresent(deps, "configured", "optional");
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("FAIL TOML 解析异常：" + t);
            failures++;
        }

        if (failures > 0) {
            System.out.println("mods.toml 校验失败：" + failures + " 项");
            System.exit(1);
        }
        System.out.println("mods.toml 校验通过");
    }

    private static int expect(String what, String expected, String actual) {
        if (expected.equals(actual)) {
            return 0;
        }
        System.out.println("FAIL " + what + " 期望=" + expected + " 实际=" + actual);
        return 1;
    }

    private static int expectNotBlank(String what, String actual) {
        if (actual != null && !actual.isBlank()) {
            return 0;
        }
        System.out.println("FAIL " + what + " 不能为空");
        return 1;
    }

    private static int expectPresent(List<Config> deps, String modId, String type) {
        for (Config dep : deps) {
            if (modId.equals(dep.get("modId"))) {
                String actualType = dep.get("type");
                if (type.equals(actualType)) {
                    return 0;
                }
                System.out.println("FAIL 依赖 " + modId + " 的 type 期望=" + type + " 实际=" + actualType);
                return 1;
            }
        }
        System.out.println("FAIL 缺少依赖声明：" + modId);
        return 1;
    }

    private TomlCheck() {}
}
