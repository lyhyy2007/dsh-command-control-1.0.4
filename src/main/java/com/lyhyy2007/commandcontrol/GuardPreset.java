package com.lyhyy2007.commandcontrol;

import java.util.Locale;

/**
 * 启用组（1.0.4 起只保留两个）。
 *
 * <p>按玩家要求精简为：
 * <ul>
 *   <li>{@link #DISABLED}「禁用指令」（<b>默认</b>）：只放行权限等级 0 的命令，
 *       其余命令在解析期就被命令树闸门截断（补全里也不出现），执行层再兜一层；</li>
 *   <li>{@link #NO_RESTRICTION}「不禁用指令」：<b>完全不碰命令树</b>，所有命令照常可见可用
 *       （自定义禁用列表仍在执行前生效，属于“按名单精确拦”，不会让整条命令消失）。</li>
 * </ul>
 *
 * <p>原来的「仅传送」「传送+天气+时间」两个预设已删除：旧的配置文件里如果还写着
 * {@code TELEPORT_ONLY} / {@code TELEPORT_WEATHER_TIME}，会被 NightConfig 按“非法枚举值”
 * 自动纠正为新默认值 {@link #DISABLED}（迁移日志里也会说明）。
 */
public enum GuardPreset {
    /** 预设 1：禁用指令（默认为此）。 */
    DISABLED(1, "禁用指令",
            "只放行权限等级 0 的命令（/help /list /me /msg /tell /w /teammsg /tm /trigger）+ 本模组管理命令；"
                    + "其余命令在解析期被截断，Tab 补全里也不会出现",
            0),

    /** 预设 2：不禁用指令。 */
    NO_RESTRICTION(2, "不禁用指令",
            "所有命令照常可用（命令树完全不动，补全与执行都不受影响）；"
                    + "自定义禁用列表仍然会在执行前精确拦截",
            4);

    private final int id;
    private final String displayName;
    private final String description;
    private final int highestAllowedLevel;

    GuardPreset(int id, String displayName, String description, int highestAllowedLevel) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.highestAllowedLevel = highestAllowedLevel;
    }

    /** 配置/命令里使用的编号（1~2）。 */
    public int id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    /** 该预设允许的最高权限等级（配置界面提示用）。 */
    public int highestAllowedLevel() {
        return highestAllowedLevel;
    }

    /**
     * 该预设是否放行某条命令。
     *
     * @param root  命令根名（已小写、已去掉命名空间）
     * @param level 该命令的权限等级（0~4，未知命令为配置的兜底值）
     */
    public boolean allows(String root, int level) {
        if (this == NO_RESTRICTION) {
            return true;
        }
        return level == CommandPermissions.LEVEL_ALL;
    }

    /** 是否需要在命令树上装闸门（只有“禁用指令”需要）。 */
    public boolean needsCommandTreeGate() {
        return this == DISABLED;
    }

    /** 按编号取预设，越界时回落到 {@link #DISABLED}（保守）。 */
    public static GuardPreset byId(int id) {
        for (GuardPreset preset : values()) {
            if (preset.id == id) {
                return preset;
            }
        }
        return DISABLED;
    }

    /** 按枚举名、显示名或编号解析，失败返回 {@code null}。 */
    public static GuardPreset parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        for (GuardPreset preset : values()) {
            if (preset.name().equalsIgnoreCase(trimmed) || preset.displayName.equals(trimmed)) {
                return preset;
            }
        }
        try {
            int id = Integer.parseInt(trimmed);
            for (GuardPreset preset : values()) {
                if (preset.id == id) {
                    return preset;
                }
            }
        } catch (NumberFormatException ignored) {
            // 落到下面返回 null
        }
        return null;
    }

    /** 命令补全候选。 */
    public static String[] suggestions() {
        GuardPreset[] values = values();
        String[] out = new String[values.length * 2];
        for (int i = 0; i < values.length; i++) {
            out[i * 2] = values[i].name().toLowerCase(Locale.ROOT);
            out[i * 2 + 1] = Integer.toString(values[i].id);
        }
        return out;
    }
}
