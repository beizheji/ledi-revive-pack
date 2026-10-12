package com.ledi.agent.net;

import com.ledi.agent.sdk.SdkController;
import com.ledi.agent.voice.TtsController;
import com.ledi.agent.voice.VoiceDialog;
import com.ledi.agent.voice.VoiceWakeController;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 动态代理（{@link Proxy}）的两条铁律 —— 违反任何一条，运行时都会抛
 *
 * <pre>
 *   NullPointerException: Expected to unbox a 'int' primitive type
 *                         but was returned null
 * </pre>
 *
 * <p>而它会被 {@code Proxy} 包成 {@code UndeclaredThrowableException}、再被
 * {@code Method.invoke} 包成 {@code InvocationTargetException}，
 * 一层层包完之后日志里只剩一句类名。灾难性在于：<b>接口里一个字都没写错</b>，
 * 编译过、注册成功、方法名也对，功能却从头到尾没通过一次 —— 标准的假绿灯。</p>
 *
 * <h3>铁律一：Object 的三个约定方法必须真实现</h3>
 * 代理对象天然继承 {@code Object}，所以 {@code hashCode()} / {@code equals()} /
 * {@code toString()} 也会走 {@code invoke()}。SDK 只要把 listener 塞进
 * {@code HashSet}/{@code HashMap}（或 {@code List.remove(o)}），
 * 就会调用 {@code hashCode()} 或 {@code equals()}。
 * 如果 handler 用的是「不是我要的方法名就 return null」，
 * {@code hashCode()} 就会返回 null → 在调用点拆箱成 {@code int} → NPE。
 *
 * <h3>铁律二：返回 primitive 的方法不许返回 null</h3>
 * 挡不住铁律一时至少要靠这条兜底；将来 SDK 换接口也不会再用同一种方式静默失败。
 *
 * <h3>用法</h3>
 * <pre>
 *   new InvocationHandler() {
 *       public Object invoke(Object p, Method m, Object[] a) {
 *           Object g = Proxies.guard(p, m, a);          // ← 先过约定方法
 *           if (g != Proxies.UNHANDLED) return g;
 *           if ("onEvent".equals(m.getName())) { ...; return null; }
 *           return Proxies.nil(m);                      // ← 末尾不许裸 return null
 *       }
 *   }
 * </pre>
 *
 * <p>本项目全部 6 处代理（AsrProbe / SdkController×2 / TtsController×2 /
 * VoiceDialog / VoiceWakeController）都按这个形状收口。
 * v76 就是 {@code AsrProbe} 那一处漏了铁律一，导致 {@code /asrtest} 从来没成功过。</p>
 */
public final class Proxies {

    /** 哨兵：表示「不是 Object 约定方法，交回调用方自己的逻辑」。 */
    public static final Object UNHANDLED = new Object();

    private Proxies() {}

    /**
     * 先处理 {@code Object} 的三个约定方法。
     *
     * @return 命中时返回真值；未命中返回 {@link #UNHANDLED}（用 {@code ==} 比较，不要用 equals）
     */
    public static Object guard(Object proxy, Method m, Object[] a) {
        String n = m.getName();
        int argc = a == null ? 0 : a.length;
        if ("hashCode".equals(n) && argc == 0) {
            return Integer.valueOf(System.identityHashCode(proxy));
        }
        if ("equals".equals(n) && argc == 1) {
            // 代理的相等语义就用同一性 —— 反正每个代理实例都是唯一一份
            return Boolean.valueOf(proxy == a[0]);
        }
        if ("toString".equals(n) && argc == 0) {
            return "LediProxy@" + Integer.toHexString(System.identityHashCode(proxy));
        }
        return UNHANDLED;
    }

    /**
     * 末尾收口用：方法返回 primitive 时给一个合法默认值，引用类型才允许 null。
     *
     * <p>写 {@code return Proxies.nil(m);} 而不是 {@code return null;}，
     * 是为了让「漏实现了某个方法」表现为<b>安静但仍然正确</b>，
     * 而不是在调用点炸出一个谁也看不懂的 NPE。</p>
     */
    public static Object nil(Method m) {
        return defaultForPrimitive(m == null ? null : m.getReturnType());
    }

    static Object defaultForPrimitive(Class<?> rt) {
        if (rt == null || !rt.isPrimitive() || rt == void.class) return null;
        if (rt == boolean.class) return Boolean.FALSE;
        if (rt == int.class)     return Integer.valueOf(0);
        if (rt == long.class)    return Long.valueOf(0L);
        if (rt == short.class)   return Short.valueOf((short) 0);
        if (rt == byte.class)    return Byte.valueOf((byte) 0);
        if (rt == char.class)    return Character.valueOf('\0');
        if (rt == float.class)   return Float.valueOf(0f);
        if (rt == double.class)  return Double.valueOf(0d);
        return null;
    }
}
