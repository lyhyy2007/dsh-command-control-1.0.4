package com.lyhyy2007.commandcontrol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一条命令匹配模式，支持“单个命令”与“带参数的命令”。
 *
 * <p>语法（按空白切分成词元）：
 * <ul>
 *   <li>{@code give} —— 匹配 {@code /give ...} 的所有用法（词元前缀匹配）；</li>
 *   <li>{@code give @a diamond} —— 只匹配前三个词元完全一致的命令；</li>
 *   <li>{@code tp * 0 0} —— {@code *} 匹配“任意一个词元”；</li>
 *   <li>{@code say hello **} —— {@code **} 只能放在末尾，匹配“其后任意个词元（可以为 0 个）”；</li>
 *   <li>{@code ftbteams party create} —— 同样适用于模组命令的多级子命令。</li>
 * </ul>
 *
 * <p>匹配忽略大小写；命令名里的命名空间前缀（{@code minecraft:give}）在比较时会被去掉。
 */
public final class CommandPattern {

    /** 单个词元通配符。 */
    private static final String ANY_ONE = "*";
    /** 末尾“其余全部”通配符。 */
    private static final String ANY_REST = "**";

    private final String raw;
    private final List<String> tokens;
    private final boolean restWildcard;

    private CommandPattern(String raw, List<String> tokens, boolean restWildcard) {
        this.raw = raw;
        this.tokens = List.copyOf(tokens);
        this.restWildcard = restWildcard;
    }

    /**
     * 编译一条模式；空白字符串返回 {@code null}（调用方应忽略）。
     */
    public static CommandPattern compile(String rawPattern) {
        if (rawPattern == null) {
            return null;
        }
        String text = rawPattern.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.startsWith("/")) {
            text = text.substring(1).trim();
        }
        if (text.isEmpty()) {
            return null;
        }
        String[] split = text.split("\\s+");
        List<String> tokens = new ArrayList<>(split.length);
        boolean rest = false;
        for (int i = 0; i < split.length; i++) {
            String token = split[i];
            if (ANY_REST.equals(token)) {
                // ** 只在末尾有意义；出现在中间时一律当作“其后全部”，并忽略剩余词元。
                rest = true;
                break;
            }
            tokens.add(token.toLowerCase(Locale.ROOT));
        }
        if (tokens.isEmpty() && !rest) {
            return null;
        }
        return new CommandPattern(text, tokens, rest);
    }

    /**
     * 用命令的词元序列做前缀匹配。
     *
     * @param commandTokens 已按空白切分、已去掉开头的 {@code /} 的命令词元
     */
    public boolean matches(List<String> commandTokens) {
        if (commandTokens == null || commandTokens.isEmpty()) {
            return false;
        }
        if (!restWildcard && commandTokens.size() < tokens.size()) {
            return false;
        }
        if (restWildcard && commandTokens.size() < tokens.size()) {
            return false;
        }
        for (int i = 0; i < tokens.size(); i++) {
            String pattern = tokens.get(i);
            String actual = commandTokens.get(i);
            if (ANY_ONE.equals(pattern)) {
                continue;
            }
            if (!tokenMatches(pattern, actual)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 单个词元比较：先按原样（忽略大小写）比，再去掉命名空间前缀比一次。
     * 这样 {@code give} 与 {@code minecraft:give} 互相都能命中。
     */
    private static boolean tokenMatches(String pattern, String actual) {
        String p = pattern.toLowerCase(Locale.ROOT);
        String a = actual == null ? "" : actual.toLowerCase(Locale.ROOT);
        if (p.equals(a)) {
            return true;
        }
        return normalize(p).equals(normalize(a));
    }

    /** 去掉命名空间前缀并转小写。 */
    public static String normalize(String token) {
        if (token == null) {
            return "";
        }
        String value = token.toLowerCase(Locale.ROOT);
        int colon = value.indexOf(':');
        // 只在形如 "minecraft:give" 这种“单个词元内带命名空间”的情况下裁剪。
        if (colon > 0 && colon < value.length() - 1 && value.indexOf('/', colon) < 0) {
            String namespace = value.substring(0, colon);
            if (namespace.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.')) {
                return value.substring(colon + 1);
            }
        }
        return value;
    }

    /** 把原始命令字符串切分成词元（去掉开头的 {@code /}）。 */
    public static List<String> tokenize(String commandLine) {
        if (commandLine == null) {
            return List.of();
        }
        String text = commandLine.trim();
        if (text.startsWith("/")) {
            text = text.substring(1).trim();
        }
        if (text.isEmpty()) {
            return List.of();
        }
        String[] split = text.split("\\s+");
        List<String> tokens = new ArrayList<>(split.length);
        for (String token : split) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /** 原始模式文本，用于回显与日志。 */
    public String raw() {
        return raw;
    }

    /** 是否是“只点名一条命令、不带参数”的模式（例如 {@code give}），可在解析期直接整条截断。 */
    public boolean targetsOnly(String root) {
        if (restWildcard || tokens.size() != 1) {
            return false;
        }
        String only = tokens.get(0);
        if (ANY_ONE.equals(only)) {
            return false;
        }
        return only.equals(normalize(root));
    }

    /** 该模式是否针对这个根命令（第一个词元就是它），用于解析期“先放行、执行期再精确判定”。 */
    public boolean targetsRoot(String root) {
        if (tokens.isEmpty()) {
            return false;
        }
        String first = tokens.get(0);
        if (ANY_ONE.equals(first)) {
            return true;
        }
        return first.equals(normalize(root));
    }

    /** 模式词元数（诊断输出用）。 */
    public int tokenCount() {
        return restWildcard ? tokens.size() + 1 : tokens.size();
    }

    @Override
    public String toString() {
        return raw;
    }
}
