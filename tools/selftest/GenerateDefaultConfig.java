import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.WritingMode;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.lyhyy2007.commandcontrol.config.CommandControlConfig;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 用 {@link CommandControlConfig} 的 ModConfigSpec 生成一份“默认配置样例”。
 *
 * <p>做法和 FML 加载配置时一致：把 spec 的默认值 + 注释写进一个 CommentedFileConfig。
 * 这样文档里的配置示例永远和代码同步，而不是手抄的。
 */
public final class GenerateDefaultConfig {

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "docs/default-config-example.toml");
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        try (CommentedFileConfig config = CommentedFileConfig.builder(out, TomlFormat.instance())
                .preserveInsertionOrder()
                .writingMode(WritingMode.REPLACE)
                .build()) {
            int corrections = CommandControlConfig.SPEC.correct(config, (action, path, oldValue, newValue) -> {
                // 首次生成时所有键都会被“补正”，这里不需要逐个记录
            });
            config.save();
            System.out.println("默认配置已生成：" + out.toAbsolutePath() + "（补正 " + corrections + " 处）");
        }
    }

    private GenerateDefaultConfig() {}
}
