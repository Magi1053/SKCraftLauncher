/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.launch;

import com.skcraft.launcher.util.Environment;
import com.skcraft.launcher.util.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Tlhelp32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import lombok.extern.java.Log;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * Blocks until the game looks ready to show: a real window on Windows, or a
 * log heuristic elsewhere. Timeout still returns so launch can proceed.
 */
@Log
public final class GameWindowWatcher {

    private static final long TIMEOUT_MS = 90_000L;
    private static final long POLL_MS = 50L;
    private static final int WS_EX_TOOLWINDOW = 0x00000080;
    /** Skip tiny helper HWNDs; NeoForge earlydisplay is typically 854x480. */
    private static final int MIN_WINDOW_SIZE = 64;

    public enum Result {
        READY,
        PROCESS_DIED,
        TIMEOUT
    }

    private GameWindowWatcher() {
    }

    public static Result waitUntilReady(Process process, GameLogReadySignal logSignal)
            throws InterruptedException {
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + TIMEOUT_MS;
        boolean windows = Environment.detectPlatform() == Platform.WINDOWS;
        boolean win32Failed = false;

        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                return Result.PROCESS_DIED;
            }

            if (windows && !win32Failed) {
                try {
                    if (findGameWindow(process) != null) {
                        log.info("Game window detected after "
                                + (System.currentTimeMillis() - startedAt) + " ms");
                        return Result.READY;
                    }
                } catch (Throwable t) {
                    log.log(Level.WARNING, "Win32 window poll failed; using log signal too", t);
                    win32Failed = true;
                }
            }

            // Non-Windows primary signal; also backup on Windows if HWND poll fails.
            if ((!windows || win32Failed) && logSignal != null && logSignal.isSignaled()) {
                log.info("Game log readiness after "
                        + (System.currentTimeMillis() - startedAt) + " ms");
                return Result.READY;
            }

            Thread.sleep(POLL_MS);
        }

        return process.isAlive() ? Result.TIMEOUT : Result.PROCESS_DIED;
    }

    /**
     * Maximize the visible game window belonging to the process tree.
     *
     * @param process the launched game process
     */
    public static void maximizeGameWindow(Process process) {
        if (Environment.detectPlatform() != Platform.WINDOWS) {
            return;
        }

        try {
            HWND gameWindow = findGameWindow(process);
            if (gameWindow != null) {
                User32.INSTANCE.ShowWindow(gameWindow, WinUser.SW_MAXIMIZE);
            }
        } catch (Throwable t) {
            log.log(Level.WARNING, "Failed to maximize game window", t);
        }
    }

    private static HWND findGameWindow(Process process) {
        Set<Integer> pids = collectProcessTree((int) process.pid());
        AtomicReference<HWND> found = new AtomicReference<HWND>();

        User32.INSTANCE.EnumWindows((hWnd, data) -> {
            IntByReference processId = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hWnd, processId);
            if (!pids.contains(processId.getValue())) {
                return true;
            }
            // NeoForge earlydisplay creates the GLFW window with GLFW_VISIBLE=0 and
            // only calls glfwShowWindow after GL/font init — often several seconds later.
            // Require a real-sized top-level window instead of visibility.
            if (hasOwner(hWnd)) {
                return true;
            }
            if ((User32.INSTANCE.GetWindowLong(hWnd, WinUser.GWL_EXSTYLE) & WS_EX_TOOLWINDOW) != 0) {
                return true;
            }

            RECT rect = new RECT();
            if (!User32.INSTANCE.GetWindowRect(hWnd, rect)) {
                return true;
            }
            int width = Math.abs(rect.right - rect.left);
            int height = Math.abs(rect.bottom - rect.top);
            if (width < MIN_WINDOW_SIZE || height < MIN_WINDOW_SIZE) {
                return true;
            }

            found.set(hWnd);
            return false;
        }, null);

        return found.get();
    }

    private static Set<Integer> collectProcessTree(int rootPid) {
        Set<Integer> pids = new LinkedHashSet<Integer>();
        pids.add(rootPid);

        Map<Integer, Integer> parentByPid = new HashMap<Integer, Integer>();
        HANDLE snapshot = Kernel32.INSTANCE.CreateToolhelp32Snapshot(
                Tlhelp32.TH32CS_SNAPPROCESS, new DWORD(0));
        if (snapshot == null || WinBase.INVALID_HANDLE_VALUE.equals(snapshot)) {
            return pids;
        }

        try {
            Tlhelp32.PROCESSENTRY32 entry = new Tlhelp32.PROCESSENTRY32();
            if (Kernel32.INSTANCE.Process32First(snapshot, entry)) {
                do {
                    parentByPid.put(entry.th32ProcessID.intValue(), entry.th32ParentProcessID.intValue());
                } while (Kernel32.INSTANCE.Process32Next(snapshot, entry));
            }
        } finally {
            Kernel32.INSTANCE.CloseHandle(snapshot);
        }

        boolean grew;
        do {
            grew = false;
            for (Map.Entry<Integer, Integer> entry : parentByPid.entrySet()) {
                if (pids.contains(entry.getValue()) && pids.add(entry.getKey())) {
                    grew = true;
                }
            }
        } while (grew);

        return pids;
    }

    private static boolean hasOwner(HWND hWnd) {
        HWND owner = User32.INSTANCE.GetWindow(hWnd, new DWORD(WinUser.GW_OWNER));
        return owner != null
                && owner.getPointer() != null
                && Pointer.nativeValue(owner.getPointer()) != 0;
    }
}
