package com.lyhyy2007.commandcontrol;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * 命令树包装器（与 Minecraft 类型无关，泛型 {@code S} 便于离线自检）。
 *
 * <p>做法：把每个字面量根命令换成“同名 + requirement 追加一层闸门 + 同一批子节点”的新节点。
 * 判定不过时 brigadier 在解析阶段就把该命令当不存在。
 *
 * <p><b>为什么要单独抽出来 + 为什么要回滚</b>：1.0.2 的写法曾经让命令（如 {@code /tp}、{@code /time}）
 * 在“不禁用”模式下也彻底消失——命令树手术一旦出错，玩家就再也敲不出这条命令。
 * 因此这里做三件事：
 * <ol>
 *   <li>先记住原始节点；逐个替换后<b>校验</b>“根节点数量不变、每个原名字都还能找到、包装节点确实生效”；</li>
 *   <li>任何异常或不一致 → <b>整体回滚</b>（把原始节点放回去），命令树恢复原样；</li>
 *   <li>返回结果（含包装前的原始 requirement）供调用方使用，绝不吞掉问题。</li>
 * </ol>
 *
 * <p>brigadier 1.3.11 细节（对着字节码核对）：{@code CommandNode} 没有 {@code setRequirement}，
 * {@code addChild} 对同名节点是“合并”而非替换，所以必须先
 * {@code root.getChildren().remove(old)}（{@code getChildren()} 返回底层 children map 的
 * values() 视图，可删除）再 {@code addChild(wrapped)}。
 */
public final class CommandTreeGate {

    /** 安装结果。{@code originals} 是“包装前”的 requirement，权限探测必须用它，否则会递归。 */
    public record Result<S>(boolean installed,
                            boolean rolledBack,
                            int wrappedCount,
                            Set<String> wrappedNames,
                            Map<String, Predicate<S>> originals,
                            String message) {
    }

    private CommandTreeGate() {}

    /**
     * 把 {@code dispatcher} 的根命令逐个包上闸门 {@code gate}。
     *
     * @param gate      入参为（命令名, 命令源）；返回 true 表示放行；抛异常时按“只跑原 requirement”处理
     * @param skipNames 不包装的根命令名（小写），例如本模组的管理命令
     */
    public static <S> Result<S> install(CommandDispatcher<S> dispatcher,
                                        BiPredicate<String, S> gate,
                                        Set<String> skipNames) {
        RootCommandNode<S> root = dispatcher.getRoot();
        List<CommandNode<S>> originalChildren = new ArrayList<>(root.getChildren());
        Set<String> originalNames = namesOf(originalChildren);
        Map<String, CommandNode<S>> wrapped = new LinkedHashMap<>();
        Map<String, Predicate<S>> originals = new LinkedHashMap<>();

        try {
            for (CommandNode<S> child : originalChildren) {
                if (!(child instanceof LiteralCommandNode<S> literal)) {
                    continue;
                }
                String key = literal.getLiteral().toLowerCase(Locale.ROOT);
                if (skipNames != null && skipNames.contains(key)) {
                    continue;
                }
                Predicate<S> original = literal.getRequirement();
                originals.put(key, original);
                LiteralCommandNode<S> replacement = new LiteralCommandNode<>(
                        literal.getLiteral(),
                        literal.getCommand(),
                        source -> {
                            try {
                                return original.test(source) && gate.test(literal.getLiteral(), source);
                            } catch (Throwable t) {
                                // 闸门异常一律放行：绝不能让一条命令因为本模组而变成“不存在”
                                CommandControl.LOGGER.error("[{}] 闸门判定异常，已放行 /{}",
                                        CommandControl.MODID, literal.getLiteral(), t);
                                return original.test(source);
                            }
                        },
                        literal.getRedirect(),
                        literal.getRedirectModifier(),
                        literal.isFork());
                for (CommandNode<S> grandChild : literal.getChildren()) {
                    replacement.addChild(grandChild);
                }
                root.getChildren().remove(literal);
                root.addChild(replacement);
                wrapped.put(key, replacement);
            }

            // ------------------------------ 校验：命令树必须仍然完整
            String problem = verify(root, originalNames, originalChildren, wrapped);
            if (problem != null) {
                rollback(root, originalChildren, wrapped);
                return new Result<>(false, true, 0, Set.of(), Map.of(), "命令树校验失败，已回滚：" + problem);
            }
            return new Result<>(true, false, wrapped.size(), Set.copyOf(wrapped.keySet()),
                    Map.copyOf(originals), "已包装 " + wrapped.size() + " 个根命令");
        } catch (Throwable t) {
            try {
                rollback(root, originalChildren, wrapped);
            } catch (Throwable rollbackFailure) {
                CommandControl.LOGGER.error("[{}] 闸门回滚也失败了，命令表可能不完整",
                        CommandControl.MODID, rollbackFailure);
            }
            CommandControl.LOGGER.error("[{}] 安装命令树闸门失败，已回滚：{}", CommandControl.MODID, t.toString());
            return new Result<>(false, true, 0, Set.of(), Map.of(), "安装失败，已回滚：" + t);
        }
    }

    /** 校验：数量不变、原名字都在、包装节点确实生效。 */
    private static <S> String verify(RootCommandNode<S> root,
                                     Set<String> originalNames,
                                     List<CommandNode<S>> originalChildren,
                                     Map<String, CommandNode<S>> wrapped) {
        if (root.getChildren().size() != originalChildren.size()) {
            return "根命令数量从 " + originalChildren.size() + " 变成 " + root.getChildren().size();
        }
        for (String name : originalNames) {
            CommandNode<S> node = root.getChild(name);
            if (node == null) {
                return "根命令 /" + name + " 在包装后找不到了";
            }
            if (wrapped.containsKey(name) && node != wrapped.get(name)) {
                return "根命令 /" + name + " 没有换成包装节点";
            }
            if (!wrapped.containsKey(name) && node != findByName(originalChildren, name)) {
                return "未包装的根命令 /" + name + " 被意外替换";
            }
        }
        return null;
    }

    /** 把原始节点放回原位（先摘掉包装节点，再放回原节点）。 */
    private static <S> void rollback(RootCommandNode<S> root,
                                     List<CommandNode<S>> originalChildren,
                                     Map<String, CommandNode<S>> wrapped) {
        for (CommandNode<S> replacement : wrapped.values()) {
            root.getChildren().remove(replacement);
        }
        for (CommandNode<S> original : originalChildren) {
            root.addChild(original);
        }
    }

    private static <S> CommandNode<S> findByName(List<CommandNode<S>> nodes, String name) {
        for (CommandNode<S> node : nodes) {
            if (node.getName().toLowerCase(Locale.ROOT).equals(name)) {
                return node;
            }
        }
        return null;
    }

    private static <S> Set<String> namesOf(List<CommandNode<S>> nodes) {
        Set<String> names = new LinkedHashSet<>();
        for (CommandNode<S> node : nodes) {
            names.add(node.getName().toLowerCase(Locale.ROOT));
        }
        return names;
    }
}
