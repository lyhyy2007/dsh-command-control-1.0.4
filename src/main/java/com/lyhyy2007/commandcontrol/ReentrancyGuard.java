package com.lyhyy2007.commandcontrol;

import java.util.function.Supplier;

/**
 * 线程内“判定进行中”标记。
 *
 * <p>为什么需要它：{@link CommandGate} 把命令树根节点的 requirement 包成了
 * “原 requirement AND 本模组判定”。而 {@link CommandPermissions#probe} 为了探测模组命令的权限等级，
 * 会去调用节点的 requirement——如果不加保护，就会
 * {@code allowRoot -> resolve -> probe -> requirement -> allowRoot -> ...} 无限递归，
 * 把服务器线程直接卡死（1.0.2 实际发生过：进存档时命令补全会触发解析，然后整局无响应）。
 *
 * <p>本类提供两道保险：
 * <ol>
 *   <li>{@link #call}：一旦发现“当前线程已经在判定中”，立刻走 fallback，绝不递归；</li>
 *   <li>{@link #isActive()}：探测逻辑据此直接跳过探测。</li>
 * </ol>
 */
public final class ReentrancyGuard {

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private ReentrancyGuard() {}

    /** 当前线程是否正在执行本模组的判定。 */
    public static boolean isActive() {
        return DEPTH.get() > 0;
    }

    /** 当前线程的判定嵌套深度（诊断用）。 */
    public static int depth() {
        return DEPTH.get();
    }

    /**
     * 在“判定进行中”标记下执行 {@code body}；若当前线程已在判定中，则不执行 {@code body}，
     * 直接返回 {@code fallback} 的结果。
     */
    public static <T> T call(Supplier<T> body, Supplier<T> fallback) {
        if (isActive()) {
            return fallback.get();
        }
        DEPTH.set(DEPTH.get() + 1);
        try {
            return body.get();
        } finally {
            int remaining = DEPTH.get() - 1;
            if (remaining <= 0) {
                DEPTH.remove();
            } else {
                DEPTH.set(remaining);
            }
        }
    }
}
