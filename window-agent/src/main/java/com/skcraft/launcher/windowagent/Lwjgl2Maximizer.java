package com.skcraft.launcher.windowagent;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Reflection-only bridge so the agent has no compile-time dependency on any
 * particular LWJGL 2 build.
 */
public final class Lwjgl2Maximizer {

    private static final Set<Class<?>> APPLIED = Collections.newSetFromMap(
            new WeakHashMap<Class<?>, Boolean>());

    private Lwjgl2Maximizer() {
    }

    public static synchronized void prepare(Class<?> displayClass) {
        if (displayClass == null || APPLIED.contains(displayClass)) {
            return;
        }

        try {
            if (displayClass.getMethod("getParent").invoke(null) != null) {
                return;
            }
            if (((Boolean) displayClass.getMethod("isFullscreen").invoke(null)).booleanValue()) {
                return;
            }

            Rectangle workArea = GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds();
            if (workArea.width < 400 || workArea.height < 300) {
                return;
            }

            ClassLoader loader = displayClass.getClassLoader();
            Class<?> displayModeClass = Class.forName(
                    "org.lwjgl.opengl.DisplayMode", true, loader);
            Constructor<?> constructor = displayModeClass.getConstructor(
                    Integer.TYPE, Integer.TYPE);
            Object displayMode = constructor.newInstance(
                    Integer.valueOf(workArea.width),
                    Integer.valueOf(workArea.height));

            invokeOptional(displayClass, "setResizable", new Class<?>[] { Boolean.TYPE },
                    new Object[] { Boolean.TRUE });
            Method setDisplayMode = displayClass.getMethod("setDisplayMode", displayModeClass);
            setDisplayMode.invoke(null, displayMode);
            Method setLocation = displayClass.getMethod(
                    "setLocation", Integer.TYPE, Integer.TYPE);
            setLocation.invoke(
                    null,
                    Integer.valueOf(workArea.x),
                    Integer.valueOf(workArea.y));

            APPLIED.add(displayClass);
            System.out.println("[SKCraft Window Agent] Prepared parentless LWJGL 2 display at "
                    + workArea.width + "x" + workArea.height);
        } catch (Throwable t) {
            System.err.println("[SKCraft Window Agent] LWJGL 2 preparation skipped: " + t);
        }
    }

    private static void invokeOptional(
            Class<?> owner, String name, Class<?>[] parameterTypes, Object[] arguments) {
        try {
            owner.getMethod(name, parameterTypes).invoke(null, arguments);
        } catch (NoSuchMethodException ignored) {
            // Older LWJGL 2 builds did not expose every optional window hint.
        } catch (Throwable t) {
            System.err.println("[SKCraft Window Agent] Optional LWJGL 2 call failed: " + t);
        }
    }
}
