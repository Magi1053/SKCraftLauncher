/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.browser.swt;

import com.skcraft.launcher.browser.BrowserFallbackPanels;
import com.skcraft.launcher.browser.BrowserView;
import com.skcraft.launcher.swing.SwingHelper;
import com.skcraft.launcher.util.Environment;
import com.skcraft.launcher.util.Platform;
import com.skcraft.launcher.util.SharedLocale;
import lombok.extern.java.Log;
import net.miginfocom.swing.MigLayout;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.awt.SWT_AWT;
import org.eclipse.swt.browser.*;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.AffineTransform;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Windows/Linux embedded browser backed by SWT Browser.
 */
@Log
public final class SwtWebpageView extends JPanel implements BrowserView {

    private static final String LOADING_CARD = "loading";
    private static final String BROWSER_CARD = "browser";
    private static final String ERROR_CARD = "error";

    private final Component parentComponent;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cardPanel = new JPanel(cardLayout);
    private final JPanel loadingPanel = new JPanel();
    private final JPanel browserPanel = new JPanel(new BorderLayout());
    private final Canvas browserCanvas = new Canvas() {
        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, 0);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(0, 0);
        }
    };
    private final JPanel errorPanel = new JPanel(new GridBagLayout());

    private volatile SwtBrowserSession session;
    private volatile boolean loadingProgrammatically;
    private volatile boolean browserUnavailable;

    private URL url;
    private String html;
    private boolean darkTheme;
    private Border browserBorder;

    public SwtWebpageView(Component parentComponent) {
        this.parentComponent = parentComponent;
        setLayout(new BorderLayout());

        loadingPanel.setLayout(new MigLayout("fill, align center center", "[100!]", "[]"));
        JProgressBar progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        loadingPanel.add(progressBar, "w 100!, h 16!");

        browserPanel.add(browserCanvas, BorderLayout.CENTER);
        browserCanvas.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                layoutSwtShell();
            }

            @Override
            public void componentShown(ComponentEvent e) {
                layoutSwtShell();
            }
        });

        cardPanel.add(loadingPanel, LOADING_CARD);
        cardPanel.add(browserPanel, BROWSER_CARD);
        cardPanel.add(errorPanel, ERROR_CARD);
        cardLayout.show(cardPanel, LOADING_CARD);

        add(cardPanel, BorderLayout.CENTER);
        applyPanelBackgrounds();
    }

    private void applyPanelBackgrounds() {
        SwingHelper.applyTableBackground(this, cardPanel, loadingPanel, errorPanel);
    }

    @Override
    public Component getComponent() {
        return this;
    }

    @Override
    public void setBrowserBorder(Border border) {
        browserBorder = border;
        loadingPanel.setBorder(border);
        browserPanel.setBorder(border);
        errorPanel.setBorder(border);
    }

    @Override
    public void setDarkTheme(boolean darkTheme) {
        this.darkTheme = darkTheme;
        applyPanelBackgrounds();
        runOnSwtThread(this::applyPreferredColorScheme);
    }

    @Override
    public void load(URL url) {
        if (browserUnavailable) {
            return;
        }

        this.url = url;
        this.html = null;
        showLoading();
        runOnSwtThread(this::loadPendingContent);
    }

    @Override
    public void loadHtml(String html) {
        if (browserUnavailable) {
            return;
        }

        this.url = null;
        this.html = html;
        showLoading();
        runOnSwtThread(this::loadPendingContent);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (session == null) {
            showLoading();
            SwtBrowserSession newSession = new SwtBrowserSession(
                    browserCanvas, SWT.NONE, darkTheme, (browser, shell) -> {
                        log.info("SWT browser backend: " + browser.getBrowserType());
                        installBrowserListeners(browser, shell);
                        loadPendingContent();
                    });
            session = newSession;
            try {
                newSession.start();
                layoutSwtShell();
            } catch (LinkageError | SWTError e) {
                session = null;
                log.log(Level.WARNING, "Embedded browser is unavailable", e);
                showBrowserUnavailable();
            } catch (RuntimeException e) {
                session = null;
                log.log(Level.WARNING, "Embedded browser failed to initialize", e);
                showBrowserUnavailable();
            }
        }
    }

    @Override
    public void removeNotify() {
        disposeBrowser();
        super.removeNotify();
    }

    @Override
    public void disposeBrowser() {
        SwtBrowserSession currentSession = session;
        session = null;
        loadingProgrammatically = false;
        if (currentSession != null) {
            currentSession.stop();
        }
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, 0);
    }

    @Override
    public void doLayout() {
        super.doLayout();
        layoutSwtShell();
    }

    private void layoutSwtShell() {
        SwtBrowserSession currentSession = session;
        if (currentSession == null) {
            return;
        }

        Dimension size = getScaledCanvasSize();
        currentSession.layout(size.width, size.height);
    }

    private Dimension getScaledCanvasSize() {
        Dimension size = browserCanvas.getSize();
        GraphicsConfiguration configuration = browserCanvas.getGraphicsConfiguration();
        if (configuration == null) {
            return size;
        }

        AffineTransform transform = configuration.getDefaultTransform();
        int width = (int) Math.ceil(size.width * transform.getScaleX());
        int height = (int) Math.ceil(size.height * transform.getScaleY());
        return new Dimension(width, height);
    }

    private void installBrowserListeners(final Browser browser, final Shell shell) {
        browser.addProgressListener(new ProgressAdapter() {
            @Override
            public void changed(ProgressEvent event) {
                if (loadingProgrammatically && event.current > 0) {
                    revealBrowserIfLoading();
                }
            }

            @Override
            public void completed(ProgressEvent event) {
                finishLoading();
            }
        });

        browser.addLocationListener(new LocationAdapter() {
            @Override
            public void changing(LocationEvent event) {
                if (event.top && shouldOpenExternally(event.location)) {
                    event.doit = false;
                    openExternalUrl(event.location);
                }
            }

            @Override
            public void changed(LocationEvent event) {
                if (loadingProgrammatically && event.top) {
                    revealBrowserIfLoading();
                }
            }
        });

        browser.addOpenWindowListener(event -> {
            Shell popupShell = new Shell(shell, SWT.NO_TRIM);
            Browser popupBrowser = new Browser(popupShell, SWT.NONE);
            event.browser = popupBrowser;
            popupBrowser.addLocationListener(new LocationAdapter() {
                @Override
                public void changing(LocationEvent locationEvent) {
                    locationEvent.doit = false;
                    openExternalUrl(locationEvent.location);
                    if (!popupShell.isDisposed()) {
                        popupShell.dispose();
                    }
                }
            });
            popupBrowser.addCloseWindowListener(closeEvent -> {
                if (!popupShell.isDisposed()) {
                    popupShell.dispose();
                }
            });
        });
    }

    private void loadPendingContent() {
        Browser browser = currentBrowser();
        if (browser == null) {
            return;
        }

        if (html != null) {
            loadingProgrammatically = true;
            showLoading();
            if (browser.setText(html)) {
                finishLoading();
            } else {
                loadingProgrammatically = false;
                showError(SharedLocale.tr("news.panel.loadFailed"));
            }
            return;
        }

        if (url != null) {
            loadingProgrammatically = true;
            showLoading();
            if (!browser.setUrl(url.toExternalForm())) {
                loadingProgrammatically = false;
                showError(SharedLocale.tr("news.panel.loadFailed"));
            }
            return;
        }

        showBrowser();
    }

    private Browser currentBrowser() {
        SwtBrowserSession currentSession = session;
        if (currentSession == null) {
            return null;
        }

        Browser browser = currentSession.browser();
        return browser == null || browser.isDisposed() ? null : browser;
    }

    private void revealBrowserIfLoading() {
        if (loadingProgrammatically) {
            showBrowser();
        }
    }

    private void finishLoading() {
        if (!loadingProgrammatically && url == null && html == null) {
            return;
        }

        loadingProgrammatically = false;
        showBrowser();
        applyPreferredColorScheme();
    }

    private boolean shouldOpenExternally(String value) {
        if (loadingProgrammatically || url == null || value == null || value.trim().isEmpty()) {
            return false;
        }

        try {
            URI current = stripFragment(url.toURI());
            URI target = stripFragment(new URI(value));
            return target.isAbsolute() && !current.equals(target);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static URI stripFragment(URI uri) throws URISyntaxException {
        return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), uri.getQuery(), null);
    }

    private void applyPreferredColorScheme() {
        SwtBrowserColorScheme.apply(currentBrowser(), darkTheme);
    }

    private void runOnSwtThread(Runnable action) {
        SwtBrowserSession currentSession = session;
        if (currentSession != null) {
            currentSession.async(action);
        }
    }

    private void showLoading() {
        showCard(LOADING_CARD);
    }

    private void showBrowser() {
        showCard(BROWSER_CARD);
    }

    private void showBrowserUnavailable() {
        browserUnavailable = true;
        loadingProgrammatically = false;
        SwingUtilities.invokeLater(() -> {
            errorPanel.removeAll();
            errorPanel.add(BrowserFallbackPanels.buildUnavailablePanel(parentComponent));
            applyPanelBackgrounds();
            cardLayout.show(cardPanel, ERROR_CARD);
            errorPanel.revalidate();
            errorPanel.repaint();
            cardPanel.revalidate();
            cardPanel.repaint();
        });
    }

    private void showError(String message) {
        SwingUtilities.invokeLater(() -> {
            errorPanel.removeAll();
            errorPanel.add(BrowserFallbackPanels.buildErrorPanel(message));
            applyPanelBackgrounds();
            errorPanel.setVisible(true);
            cardLayout.show(cardPanel, ERROR_CARD);
            errorPanel.revalidate();
            errorPanel.repaint();
        });
    }

    private void showCard(String cardName) {
        if (browserUnavailable && !ERROR_CARD.equals(cardName)) {
            return;
        }

        SwingUtilities.invokeLater(() -> {
            if (browserUnavailable && !ERROR_CARD.equals(cardName)) {
                return;
            }
            cardLayout.show(cardPanel, cardName);
            cardPanel.revalidate();
            cardPanel.repaint();
        });
    }

    private void openExternalUrl(String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }

        try {
            URL popupUrl = new URL(value);
            SwingUtilities.invokeLater(() -> SwingHelper.openURL(popupUrl, parentComponent));
        } catch (MalformedURLException ignored) {
        }
    }

    private static final class SwtBrowserSession {

        private enum State {
            IDLE, RUNNING, STOPPING, STOPPED
        }

        @FunctionalInterface
        private interface InitCallback {
            void onInitialized(Browser browser, Shell shell);
        }

        private final Canvas canvas;
        private final int browserStyle;
        private final boolean initialDarkTheme;
        private final InitCallback initCallback;
        private final AtomicBoolean widgetsDisposed = new AtomicBoolean();

        private volatile State state = State.IDLE;
        private volatile Display display;
        private volatile Shell shell;
        private volatile Browser browser;
        private volatile Thread swtThread;

        private SwtBrowserSession(
                Canvas canvas,
                int browserStyle,
                boolean initialDarkTheme,
                InitCallback initCallback) {
            this.canvas = canvas;
            this.browserStyle = browserStyle;
            this.initialDarkTheme = initialDarkTheme;
            this.initCallback = initCallback;
        }

        private Browser browser() {
            return state == State.RUNNING ? browser : null;
        }

        private synchronized void start() {
            if (state != State.IDLE) {
                throw new IllegalStateException("Session already started (state: " + state + ")");
            }

            final CountDownLatch ready = new CountDownLatch(1);
            final Throwable[] initFailure = new Throwable[1];

            Thread thread = new Thread(() -> {
                try {
                    Display.setAppName(SharedLocale.tr("launcher.appTitle"));
                    display = new Display();
                    swtThread = Thread.currentThread();
                    SwtBrowserColorScheme.applyBeforeBrowserCreate(display, initialDarkTheme);
                    shell = SWT_AWT.new_Shell(display, canvas);
                    shell.setLayout(new FillLayout());
                    browser = new Browser(shell, browserStyle);
                    shell.open();
                    state = State.RUNNING;
                    initCallback.onInitialized(browser, shell);
                    ready.countDown();

                    while (shell != null && !shell.isDisposed()) {
                        if (!display.readAndDispatch()) {
                            display.sleep();
                        }
                    }
                } catch (Throwable t) {
                    initFailure[0] = t;
                    ready.countDown();
                } finally {
                    disposeOnce();
                }
            }, "SWT Browser");
            swtThread = thread;
            thread.setDaemon(true);
            thread.start();

            try {
                if (!ready.await(30, TimeUnit.SECONDS)) {
                    state = State.STOPPED;
                    disposeOnce();
                    throw new RuntimeException("Timed out waiting for SWT browser to initialize");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while initializing SWT browser", e);
            }

            if (initFailure[0] != null) {
                state = State.STOPPED;
                if (initFailure[0] instanceof LinkageError) {
                    throw (LinkageError) initFailure[0];
                }
                if (initFailure[0] instanceof SWTError) {
                    throw (SWTError) initFailure[0];
                }
                if (initFailure[0] instanceof RuntimeException) {
                    throw (RuntimeException) initFailure[0];
                }
                throw new RuntimeException("SWT browser failed to initialize", initFailure[0]);
            }
        }

        private synchronized void stop() {
            if (state == State.STOPPING || state == State.STOPPED) {
                return;
            }
            state = State.STOPPING;

            Display currentDisplay = display;
            Thread currentThread = swtThread;
            if (currentDisplay != null && !currentDisplay.isDisposed()) {
                try {
                    currentDisplay.wake();
                    if (Thread.currentThread() == currentThread) {
                        disposeOnce();
                    } else {
                        currentDisplay.syncExec(this::disposeOnce);
                    }
                } catch (RuntimeException ignored) {
                }
            }

            joinSwtThread(currentThread);
            state = State.STOPPED;
        }

        private void async(Runnable task) {
            Display currentDisplay = display;
            if (currentDisplay == null || currentDisplay.isDisposed()) {
                return;
            }

            currentDisplay.asyncExec(() -> {
                if (state == State.RUNNING) {
                    task.run();
                }
            });
        }

        private void layout(int width, int height) {
            if (width <= 0 || height <= 0) {
                return;
            }

            async(() -> {
                Shell currentShell = shell;
                if (currentShell == null || currentShell.isDisposed()) {
                    return;
                }

                currentShell.setBounds(0, 0, width, height);
                Browser currentBrowser = browser;
                if (currentBrowser != null && !currentBrowser.isDisposed()) {
                    Rectangle clientArea = currentShell.getClientArea();
                    currentBrowser.setBounds(clientArea);
                }
                currentShell.layout(true, true);
            });
        }

        private void joinSwtThread(Thread thread) {
            if (thread == null || thread == Thread.currentThread()) {
                return;
            }

            try {
                thread.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (thread.isAlive()) {
                log.log(Level.WARNING, "SWT browser thread did not shut down within 5 seconds; "
                        + "the native browser engine may still be running");
            }
        }

        private void disposeOnce() {
            if (!widgetsDisposed.compareAndSet(false, true)) {
                return;
            }

            if (browser != null && !browser.isDisposed()) {
                try {
                    browser.setUrl("about:blank");
                } catch (RuntimeException ignored) {
                }
                browser.dispose();
            }
            if (shell != null && !shell.isDisposed()) {
                shell.dispose();
            }
            if (display != null && !display.isDisposed()) {
                display.dispose();
            }

            browser = null;
            shell = null;
            display = null;
            swtThread = null;
        }
    }

    private static final class SwtBrowserColorScheme {

        // SWT Edge.java Display key; spelling matches upstream ("Prefered").
        private static final String EDGE_DARK_SCHEME_KEY =
                "org.eclipse.swt.internal.win32.Edge.useDarkPreferedColorScheme";

        private static final boolean IS_WINDOWS = Environment.detectPlatform() == Platform.WINDOWS;

        private SwtBrowserColorScheme() {
        }

        private static void applyBeforeBrowserCreate(Display display, boolean darkTheme) {
            if (IS_WINDOWS) {
                display.setData(EDGE_DARK_SCHEME_KEY, darkTheme);
            }
        }

        private static void apply(Browser browser, boolean darkTheme) {
            if (browser == null || browser.isDisposed()) {
                return;
            }
            if (!IS_WINDOWS || !applyNativeEdgeProfile(browser, darkTheme)) {
                applyJsFallback(browser, darkTheme);
            }
        }

        private static boolean applyNativeEdgeProfile(Browser browser, boolean darkTheme) {
            if (!"edge".equals(browser.getBrowserType())) {
                return false;
            }

            try {
                Field webBrowserField = Browser.class.getDeclaredField("webBrowser");
                webBrowserField.setAccessible(true);
                Object edge = webBrowserField.get(browser);
                if (edge == null) {
                    return false;
                }

                Field profileField = edge.getClass().getDeclaredField("profile");
                profileField.setAccessible(true);
                Object profile = profileField.get(edge);
                if (profile == null) {
                    return false;
                }

                Method putScheme = profile.getClass().getMethod("put_PreferredColorScheme", int.class);
                putScheme.invoke(profile, darkTheme ? 2 : 1);
                return true;
            } catch (ReflectiveOperationException ignored) {
                return false;
            }
        }

        private static void applyJsFallback(Browser browser, boolean darkTheme) {
            String preferred = darkTheme ? "dark" : "light";
            String script = """
                    (() => {
                      const preferred = '%s';
                      const noop = () => {};
                      const createMql = (media, matches) => ({
                        media,
                        matches,
                        onchange: null,
                        addListener: noop,
                        removeListener: noop,
                        addEventListener: noop,
                        removeEventListener: noop,
                        dispatchEvent: () => false
                      });

                      const originalMatchMedia = window.__launcherOriginalMatchMedia
                        || (typeof window.matchMedia === 'function' ? window.matchMedia.bind(window) : null);
                      window.__launcherOriginalMatchMedia = originalMatchMedia;

                      window.matchMedia = (query) => {
                        const media = String(query == null ? '' : query);
                        if (media.includes('prefers-color-scheme')) {
                          const matchesDark = media.includes('dark') && preferred === 'dark';
                          const matchesLight = media.includes('light') && preferred === 'light';
                          return createMql(media, matchesDark || matchesLight);
                        }

                        return originalMatchMedia ? originalMatchMedia(media) : createMql(media, false);
                      };

                      const root = document && document.documentElement;
                      if (root) {
                        root.setAttribute('data-prefers-color-scheme', preferred);
                        root.style.colorScheme = preferred;
                      }
                    })();
                    """.formatted(preferred);

            try {
                browser.evaluate(script);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
