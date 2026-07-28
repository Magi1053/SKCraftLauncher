/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.browser.platform;

import ca.weblite.webview.EmbeddedWebView;
import ca.weblite.webview.swing.WebViewComponent;
import ca.weblite.webview.swing.WebViewHeavyweightComponent;
import com.skcraft.launcher.util.HttpRequest;
import com.skcraft.launcher.util.SharedLocale;
import com.skcraft.launcher.util.WinRegistry;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.platform.win32.Guid.IID;
import com.sun.jna.platform.win32.Guid.REFIID;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.W32APIOptions;

import java.awt.Canvas;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.HierarchyBoundsAdapter;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Windows WebView2 browser configuration.
 */
public final class WindowsBrowserPlatform extends BrowserPlatform {

    private static final Logger log = Logger.getLogger(WindowsBrowserPlatform.class.getName());
    private static final String USER_DATA_ENV = "WEBVIEW2_USER_DATA_FOLDER";
    private static final IID ICOREWEBVIEW2_13_IID = new IID("{F75F09A8-667E-4983-88D6-C8773F315E84}");
    private static final long ENGINE_WEBVIEW_OFFSET = 40;
    private static final int GET_PROFILE_VTABLE_INDEX = 105;
    private static final int PUT_PREFERRED_COLOR_SCHEME_VTABLE_INDEX = 9;
    private static final int PREFERRED_COLOR_SCHEME_LIGHT = 1;
    private static final int PREFERRED_COLOR_SCHEME_DARK = 2;
    private static final String WEBVIEW2_BOOTSTRAPPER_URL = "https://go.microsoft.com/fwlink/?linkid=2124703";
    // Official Evergreen Runtime client id (same as
    // installer/windows/include/defines.nsh).
    private static final String WEBVIEW2_CLIENT_GUID = "{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}";
    private static final String[] WEBVIEW2_CLIENT_PATHS = {
            "SOFTWARE\\Microsoft\\EdgeUpdate\\Clients\\" + WEBVIEW2_CLIENT_GUID,
            "SOFTWARE\\WOW6432Node\\Microsoft\\EdgeUpdate\\Clients\\" + WEBVIEW2_CLIENT_GUID,
    };
    private static final WinReg.HKEY[] WEBVIEW2_HIVES = {
            WinReg.HKEY_LOCAL_MACHINE,
            WinReg.HKEY_CURRENT_USER,
    };
    private static final String EMBEDDED_CHILD_CLASS = "WebViewEmbedChild";
    private static final String WEBLITE_RESIZE_LISTENER = "ca.weblite.webview.swing.WebViewHeavyweightComponent$EmbeddedCanvas$1";
    private static final int SWP_NOZORDER = 0x0004;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SW_HIDE = 0;
    private static final int SW_SHOW = 5;
    private static final long BOUNDS_POLL_MS = 8L;
    private static final Pointer DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = Pointer.createConstant(-4);
    private static final Map<WebViewComponent, BoundsFix> FIXES = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Field EMBEDDED_FIELD = resolveEmbeddedField();

    private Path baseDir;

    WindowsBrowserPlatform(String osArch) {
        super(osArch);
    }

    @Override
    public boolean isHostRuntimeAvailable() {
        try {
            if (isWebView2Registered()) {
                return true;
            }
            return isWebView2PresentOnDisk();
        } catch (RuntimeException e) {
            // Defer to WebViewComponent.create() rather than a false unavailable panel.
            log.log(Level.WARNING, "Unable to probe WebView2 runtime", e);
            return true;
        }
    }

    private static boolean isWebView2Registered() {
        for (WinReg.HKEY hive : WEBVIEW2_HIVES) {
            for (String path : WEBVIEW2_CLIENT_PATHS) {
                Optional<String> version = WinRegistry.readStringOptional(hive, path, "pv");
                if (version.isPresent() && isInstalledWebView2Version(version.get())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isInstalledWebView2Version(String version) {
        if (version == null) {
            return false;
        }
        String trimmed = version.trim();
        return !trimmed.isEmpty() && !"0.0.0.0".equals(trimmed);
    }

    /**
     * Fallback when registry keys are missing/stale but the Evergreen runtime bits
     * exist.
     */
    private static boolean isWebView2PresentOnDisk() {
        String[] programFiles = {
                System.getenv("ProgramFiles(x86)"),
                System.getenv("ProgramFiles"),
        };
        for (String root : programFiles) {
            if (root == null || root.isBlank()) {
                continue;
            }
            Path applicationDir = Path.of(root, "Microsoft", "EdgeWebView", "Application");
            if (!Files.isDirectory(applicationDir)) {
                continue;
            }
            try (var stream = Files.newDirectoryStream(applicationDir)) {
                for (Path entry : stream) {
                    if (Files.isRegularFile(entry.resolve("msedgewebview2.exe"))) {
                        return true;
                    }
                }
            } catch (IOException e) {
                log.log(Level.FINE, "Unable to scan WebView2 application directory " + applicationDir, e);
            }
        }
        return false;
    }

    @Override
    public boolean canInstallDependency() {
        return true;
    }

    @Override
    public String dependencyInstallConfirmMessage() {
        return SharedLocale.tr("news.panel.install.windows.confirm");
    }

    @Override
    public DependencyInstallTask createDependencyInstallTask() {
        return new WebView2InstallTask();
    }

    @Override
    public void configureEnvironment(Path baseDir) throws IOException {
        this.baseDir = baseDir.toAbsolutePath();
        Path userDataFolder = this.baseDir.resolve("webview2").toAbsolutePath();
        Files.createDirectories(userDataFolder);
        if (!Kernel32.INSTANCE.SetEnvironmentVariable(
                USER_DATA_ENV, userDataFolder.toString())) {
            throw new IOException("Unable to set " + USER_DATA_ENV);
        }
        log.info("WebView2 user data folder: " + userDataFolder);
    }

    @Override
    public boolean applyPreferredColorScheme(WebViewComponent component, boolean darkTheme) {
        if (!(component instanceof WebViewHeavyweightComponent)) {
            return false;
        }

        CompletableFuture<Boolean> applied = new CompletableFuture<>();
        try {
            EmbeddedWebView embedded = getEmbeddedWebView(component);
            if (embedded == null) {
                return false;
            }

            component.dispatch(() -> {
                try {
                    applied.complete(setProfilePreferredColorScheme(embedded, darkTheme));
                } catch (LinkageError | RuntimeException e) {
                    applied.completeExceptionally(e);
                }
            });

            boolean updated = applied.get(2, TimeUnit.SECONDS);
            if (updated) {
                log.log(Level.FINE, "WebView2 profile prefers-color-scheme updated to {0}",
                        darkTheme ? "dark" : "light");
            }
            return updated;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ReflectiveOperationException | ExecutionException | TimeoutException
                | LinkageError | RuntimeException e) {
            log.log(Level.INFO, "Unable to update WebView2 profile color scheme", e);
            return false;
        }
    }

    @Override
    public void installBoundsFix(WebViewComponent component) {
        if (component == null || !component.isHeavyweight()) {
            return;
        }

        Canvas canvas = findCanvas(component);
        if (canvas == null) {
            log.warning("Weblite heavyweight component has no embedded Canvas");
            return;
        }

        synchronized (FIXES) {
            if (FIXES.containsKey(component)) {
                return;
            }
            BoundsFix fix = new BoundsFix(component, canvas);
            FIXES.put(component, fix);
            fix.start();
        }
    }

    @Override
    public void stopBoundsFix(WebViewComponent component) {
        if (component == null) {
            return;
        }

        BoundsFix fix;
        synchronized (FIXES) {
            fix = FIXES.remove(component);
        }
        if (fix != null) {
            fix.stop();
        }
    }

    @Override
    public void setContentVisible(WebViewComponent component, boolean visible) {
        if (component == null) {
            return;
        }

        BoundsFix fix;
        synchronized (FIXES) {
            fix = FIXES.get(component);
        }
        if (fix != null) {
            fix.setContentVisible(visible);
        }
    }

    @Override
    public String runtimePlatformKey() {
        return runtimePlatformKey("windows");
    }

    private static final class WebView2InstallTask extends DependencyInstallTask {

        @Override
        public Void call() throws Exception {
            Path bootstrapper = Files.createTempFile("MicrosoftEdgeWebview2Setup-", ".exe");
            try {
                setStatus(SharedLocale.tr("news.panel.install.windows.downloading"));
                HttpRequest request = HttpRequest.get(new URL(WEBVIEW2_BOOTSTRAPPER_URL));
                setDownload(request);
                try {
                    request.execute()
                            .expectResponseCode(200)
                            .saveContent(bootstrapper.toFile());
                } catch (java.net.UnknownHostException e) {
                    throw new IOException(
                            "Unable to download WebView2 (DNS lookup failed for "
                                    + e.getMessage()
                                    + "). Check network/DNS, or install WebView2 manually from Microsoft.",
                            e);
                } finally {
                    request.close();
                    setDownload(null);
                }

                setStatus(SharedLocale.tr("news.panel.install.windows.installing"));
                Process process = new ProcessBuilder(bootstrapper.toString(), "/install")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                int exitCode;
                try {
                    exitCode = process.waitFor();
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                    throw e;
                }
                if (exitCode != 0) {
                    throw new IOException("WebView2 installer exited with code " + exitCode);
                }
                return null;
            } finally {
                Files.deleteIfExists(bootstrapper);
            }
        }
    }

    private static Canvas findCanvas(Component component) {
        if (component instanceof Canvas) {
            return (Canvas) component;
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                Canvas canvas = findCanvas(child);
                if (canvas != null) {
                    return canvas;
                }
            }
        }
        return null;
    }

    private static final class BoundsFix {

        private final WebViewComponent component;
        private final Canvas canvas;
        private final ComponentListener resizeListener = new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                requestFit();
            }
        };
        private final HierarchyBoundsAdapter ancestorListener = new HierarchyBoundsAdapter() {
            @Override
            public void ancestorResized(HierarchyEvent event) {
                requestFit();
            }
        };
        private final HierarchyListener hierarchyListener = event -> {
            long flags = event.getChangeFlags();
            if ((flags & (HierarchyEvent.DISPLAYABILITY_CHANGED
                    | HierarchyEvent.PARENT_CHANGED
                    | HierarchyEvent.SHOWING_CHANGED)) != 0) {
                requestFit();
            }
        };

        private volatile boolean running;
        private volatile boolean webliteListenerRemoved;
        private volatile boolean boundsConfirmed;
        private volatile boolean contentVisible;
        private volatile Thread watcher;

        private BoundsFix(WebViewComponent component, Canvas canvas) {
            this.component = component;
            this.canvas = canvas;
        }

        private void start() {
            running = true;
            canvas.addComponentListener(resizeListener);
            component.addComponentListener(resizeListener);
            component.addHierarchyBoundsListener(ancestorListener);
            component.addHierarchyListener(hierarchyListener);

            Thread thread = new Thread(this::watchBounds, "webview2-bounds");
            thread.setDaemon(true);
            watcher = thread;
            thread.start();
        }

        private void stop() {
            running = false;
            canvas.removeComponentListener(resizeListener);
            component.removeComponentListener(resizeListener);
            component.removeHierarchyBoundsListener(ancestorListener);
            component.removeHierarchyListener(hierarchyListener);
            Thread thread = watcher;
            watcher = null;
            if (thread != null) {
                LockSupport.unpark(thread);
            }
        }

        private void watchBounds() {
            try {
                DpiUser32.INSTANCE.SetThreadDpiAwarenessContext(
                        DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
            } catch (Throwable e) {
                log.log(Level.FINE,
                        "Unable to set WebView2 bounds thread DPI context", e);
            }

            while (running) {
                try {
                    fitEmbeddedChild();
                } catch (Throwable e) {
                    log.log(Level.FINEST,
                            "Unable to fit WebView2 embedded child", e);
                }
                LockSupport.parkNanos(
                        TimeUnit.MILLISECONDS.toNanos(BOUNDS_POLL_MS));
            }
        }

        private void requestFit() {
            Thread thread = watcher;
            if (thread != null) {
                LockSupport.unpark(thread);
            }
        }

        private void fitEmbeddedChild() {
            if (!running || !canvas.isDisplayable()) {
                return;
            }

            Pointer canvasPointer = Native.getComponentPointer(canvas);
            if (canvasPointer == null || Pointer.nativeValue(canvasPointer) == 0) {
                return;
            }

            HWND canvasWindow = new HWND(canvasPointer);
            HWND child = User32.INSTANCE.FindWindowEx(
                    canvasWindow, null, EMBEDDED_CHILD_CLASS, null);
            if (child == null || child.getPointer() == null) {
                return;
            }

            removeWebliteResizeListener();

            RECT clientRect = new RECT();
            if (!User32.INSTANCE.GetClientRect(canvasWindow, clientRect)) {
                return;
            }

            int width = Math.max(0, clientRect.right - clientRect.left);
            int height = Math.max(0, clientRect.bottom - clientRect.top);
            if (User32.INSTANCE.SetWindowPos(
                    child, null, 0, 0, width, height,
                    SWP_NOZORDER | SWP_NOACTIVATE)) {
                confirmBoundsOnce(child, width, height);
            }
            User32.INSTANCE.ShowWindow(
                    child, contentVisible ? SW_SHOW : SW_HIDE);
        }

        private void removeWebliteResizeListener() {
            if (webliteListenerRemoved) {
                return;
            }
            for (ComponentListener listener : canvas.getComponentListeners()) {
                if (WEBLITE_RESIZE_LISTENER.equals(
                        listener.getClass().getName())) {
                    canvas.removeComponentListener(listener);
                }
            }
            webliteListenerRemoved = true;
        }

        private void confirmBoundsOnce(
                HWND child, int expectedWidth, int expectedHeight) {
            if (boundsConfirmed || expectedWidth == 0 || expectedHeight == 0) {
                return;
            }
            RECT childRect = new RECT();
            if (!User32.INSTANCE.GetClientRect(child, childRect)) {
                return;
            }
            int childWidth = childRect.right - childRect.left;
            int childHeight = childRect.bottom - childRect.top;
            if (childWidth == expectedWidth && childHeight == expectedHeight) {
                boundsConfirmed = true;
                log.info("WebView2 bounds synchronized at "
                        + childWidth + "x" + childHeight + " physical pixels");
            }
        }

        private void setContentVisible(boolean visible) {
            contentVisible = visible;
            requestFit();
        }
    }

    private interface DpiUser32 extends Library {

        DpiUser32 INSTANCE = Native.load(
                "user32", DpiUser32.class, W32APIOptions.DEFAULT_OPTIONS);

        Pointer SetThreadDpiAwarenessContext(Pointer dpiContext);
    }

    private static Field resolveEmbeddedField() {
        try {
            Field field = WebViewHeavyweightComponent.class.getDeclaredField("embedded");
            if (!field.trySetAccessible()) {
                throw new IllegalAccessException("Unable to access WebView2 embedded peer");
            }
            return field;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static EmbeddedWebView getEmbeddedWebView(WebViewComponent component)
            throws ReflectiveOperationException {
        return (EmbeddedWebView) EMBEDDED_FIELD.get(component);
    }

    private static boolean setProfilePreferredColorScheme(EmbeddedWebView embedded, boolean darkTheme) {
        long engineAddress = embedded.peer();
        if (engineAddress == 0) {
            return false;
        }

        // weblite's native Engine stores ICoreWebView2* at offset 40 on x64/ARM64.
        Pointer webView = nonNull(new Pointer(engineAddress).getPointer(ENGINE_WEBVIEW_OFFSET));
        if (webView == null) {
            return false;
        }

        PointerByReference webView13Reference = new PointerByReference();
        HRESULT result = new ComInterface(webView).QueryInterface(
                new REFIID(ICOREWEBVIEW2_13_IID),
                webView13Reference);
        if (failed(result)) {
            log.info("ICoreWebView2_13 QueryInterface failed: " + hresult(result));
            return false;
        }

        Pointer webView13Pointer = nonNull(webView13Reference.getValue());
        if (webView13Pointer == null) {
            return false;
        }

        ComInterface webView13 = new ComInterface(webView13Pointer);
        try {
            PointerByReference profileReference = new PointerByReference();
            result = webView13.invoke(GET_PROFILE_VTABLE_INDEX, profileReference);
            if (failed(result)) {
                log.info("ICoreWebView2_13::get_Profile failed: " + hresult(result));
                return false;
            }

            Pointer profilePointer = nonNull(profileReference.getValue());
            if (profilePointer == null) {
                return false;
            }

            ComInterface profile = new ComInterface(profilePointer);
            try {
                int scheme = darkTheme
                        ? PREFERRED_COLOR_SCHEME_DARK
                        : PREFERRED_COLOR_SCHEME_LIGHT;
                result = profile.invoke(PUT_PREFERRED_COLOR_SCHEME_VTABLE_INDEX, scheme);
                if (failed(result)) {
                    log.info("ICoreWebView2Profile::put_PreferredColorScheme failed: "
                            + hresult(result));
                    return false;
                }
                return true;
            } finally {
                profile.Release();
            }
        } finally {
            webView13.Release();
        }
    }

    private static Pointer nonNull(Pointer pointer) {
        return pointer == null || Pointer.nativeValue(pointer) == 0 ? null : pointer;
    }

    private static boolean failed(HRESULT result) {
        return result == null || result.intValue() < 0;
    }

    private static String hresult(HRESULT result) {
        return result == null ? "null" : String.format("0x%08X", result.intValue());
    }

    private static final class ComInterface extends Unknown {

        private ComInterface(Pointer pointer) {
            super(pointer);
        }

        private HRESULT invoke(int vtableIndex, Object... arguments) {
            Object[] nativeArguments = new Object[arguments.length + 1];
            nativeArguments[0] = getPointer();
            System.arraycopy(arguments, 0, nativeArguments, 1, arguments.length);
            return (HRESULT) _invokeNativeObject(
                    vtableIndex, nativeArguments, HRESULT.class);
        }
    }
}
