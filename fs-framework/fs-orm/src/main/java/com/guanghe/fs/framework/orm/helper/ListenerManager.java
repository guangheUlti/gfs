package com.guanghe.fs.framework.orm.helper;

import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * 监听器管理
 * <p>
 * 考虑任务调度、三方接口回调等可自由控制审计字段
 */
@Slf4j
public class ListenerManager {

    private ListenerManager() {
    }

    private static ThreadLocal<Boolean> ignoreInsertListenerTl = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static ThreadLocal<Boolean> ignoreUpdateListenerTl = ThreadLocal.withInitial(() -> Boolean.FALSE);


    /**
     * 设置 InsertListenerThreadLocal
     *
     * @param tl ThreadLocal
     */
    public static synchronized void setInsertListenerTl(ThreadLocal<Boolean> tl) {
        ignoreInsertListenerTl = tl;
    }

    /**
     * 设置 UpdateListenerThreadLocal
     *
     * @param tl ThreadLocal
     */
    public static synchronized void setUpdateListenerTl(ThreadLocal<Boolean> tl) {
        ignoreUpdateListenerTl = tl;
    }

    /**
     * 是否执行 InsertListener
     *
     * @return 是否执行
     */
    public static boolean isDoInsertListener() {
        return !ignoreInsertListenerTl.get();
    }

    /**
     * 是否执行 UpdateListener
     *
     * @return 是否执行
     */
    public static boolean isDoUpdateListener() {
        return !ignoreUpdateListenerTl.get();
    }

    /**
     * 忽略 Listener
     */
    public static <T> T withoutListener(Supplier<T> supplier) {
        try {
            ignoreListener();
            return supplier.get();
        } finally {
            restoreListener();
        }
    }

    /**
     * 忽略 Listener
     */
    public static void withoutListener(Runnable runnable) {
        try {
            ignoreListener();
            runnable.run();
        } finally {
            restoreListener();
        }
    }


    /**
     * 忽略 Listener
     */
    public static void ignoreListener() {
        ignoreInsertListenerTl.set(Boolean.TRUE);
        ignoreUpdateListenerTl.set(Boolean.TRUE);
    }


    /**
     * 恢复 Listener
     */
    public static void restoreListener() {
        ignoreInsertListenerTl.remove();
        ignoreUpdateListenerTl.remove();
    }


    /**
     * 忽略 InsertListener
     */
    public static <T> T withoutInsertListener(Supplier<T> supplier) {
        try {
            ignoreInsertListener();
            return supplier.get();
        } finally {
            restoreInsertListener();
        }
    }

    /**
     * 忽略 InsertListener
     */
    public static void withoutInsertListener(Runnable runnable) {
        try {
            ignoreInsertListener();
            runnable.run();
        } finally {
            restoreInsertListener();
        }
    }


    /**
     * 忽略 InsertListener
     */
    public static void ignoreInsertListener() {
        ignoreInsertListenerTl.set(Boolean.TRUE);
    }


    /**
     * 恢复 InsertListener
     */
    public static void restoreInsertListener() {
        ignoreInsertListenerTl.remove();
    }


    /**
     * 忽略 UpdateListener
     */
    public static <T> T withoutUpdateListener(Supplier<T> supplier) {
        try {
            ignoreUpdateListener();
            return supplier.get();
        } finally {
            restoreUpdateListener();
        }
    }

    /**
     * 忽略 UpdateListener
     */
    public static void withoutUpdateListener(Runnable runnable) {
        try {
            ignoreUpdateListener();
            runnable.run();
        } finally {
            restoreUpdateListener();
        }
    }


    /**
     * 忽略 UpdateListener
     */
    public static void ignoreUpdateListener() {
        ignoreUpdateListenerTl.set(Boolean.TRUE);
    }


    /**
     * 恢复 UpdateListener
     */
    public static void restoreUpdateListener() {
        ignoreUpdateListenerTl.remove();
    }
}
