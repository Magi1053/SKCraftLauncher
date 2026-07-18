/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.browser;

import com.formdev.flatlaf.FlatLaf;
import com.skcraft.launcher.browser.mac.MacWkWebpageView;
import com.skcraft.launcher.browser.swt.SwtWebpageView;
import com.skcraft.launcher.swing.SwingHelper;
import lombok.extern.java.Log;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.net.URL;
import java.util.logging.Level;

/**
 * Swing facade for the platform-selected embedded browser.
 */
@Log
public final class WebpagePanel extends JPanel {

    private URL url;
    private String html;
    private boolean activated;
    private boolean darkTheme;
    private Border browserBorder = createDefaultBrowserBorder();
    private final boolean forceMissingBrowser;
    private BrowserView browserView;

    public static WebpagePanel forURL(URL url) {
        return new WebpagePanel(url);
    }

    public static WebpagePanel forHTML(String html) {
        return new WebpagePanel(html);
    }

    public static WebpagePanel missingBrowser() {
        return new WebpagePanel(true);
    }

    private WebpagePanel(URL url) {
        this.forceMissingBrowser = false;
        this.url = url;
        initialize();
    }

    private WebpagePanel(String html) {
        this.forceMissingBrowser = false;
        this.html = html;
        initialize();
    }

    public WebpagePanel() {
        this.forceMissingBrowser = false;
        initialize();
    }

    private WebpagePanel(boolean forceMissingBrowser) {
        this.forceMissingBrowser = forceMissingBrowser;
        initialize();
    }

    private void initialize() {
        setLayout(new BorderLayout());
        activateBrowser();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        setDarkTheme(FlatLaf.isLafDark());
    }

    public Border getBrowserBorder() {
        return browserBorder;
    }

    public void setBrowserBorder(Border browserBorder) {
        this.browserBorder = browserBorder;
        if (browserView != null) {
            browserView.setBrowserBorder(browserBorder);
        }
    }

    public void setDarkTheme(boolean darkTheme) {
        this.darkTheme = darkTheme;
        if (browserView != null) {
            browserView.setDarkTheme(darkTheme);
        }
    }

    public void disposeBrowser() {
        if (browserView != null) {
            browserView.disposeBrowser();
        }
    }

    private static Border createDefaultBrowserBorder() {
        Border border = UIManager.getBorder("ScrollPane.border");
        return border != null ? border : BorderFactory.createEtchedBorder();
    }

    /**
     * Browse to a URL.
     *
     * @param url         the URL
     * @param onlyChanged true to only browse if the last URL was different
     * @return true if the URL changed
     */
    public boolean browse(URL url, boolean onlyChanged) {
        if (onlyChanged && this.url != null && this.url.equals(url)) {
            return false;
        }

        this.url = url;
        this.html = null;

        if (!activated) {
            activateBrowser();
        } else if (browserView != null) {
            browserView.load(url);
        }

        return true;
    }

    private void activateBrowser() {
        if (activated) {
            return;
        }

        activated = true;
        removeAll();
        browserView = BrowserViewFactory.create(this, forceMissingBrowser);
        add(browserView.getComponent(), BorderLayout.CENTER);
        SwingHelper.removeOpaqueness(this);
        browserView.setBrowserBorder(browserBorder);
        browserView.setDarkTheme(darkTheme);

        revalidate();
        repaint();

        if (html != null) {
            browserView.loadHtml(html);
        } else if (url != null) {
            browserView.load(url);
        }
    }

    private static final class BrowserViewFactory {

        private BrowserViewFactory() {
        }

        private static BrowserView create(Component parentComponent, boolean forceMissingBrowser) {
            if (forceMissingBrowser) {
                return new MissingBrowserView(parentComponent);
            }

            try {
                switch (BrowserRuntime.detectBackend()) {
                    case WKWEBVIEW:
                        return new MacWkWebpageView(parentComponent);
                    case SWT:
                        return new SwtWebpageView(parentComponent);
                    default:
                        throw new IllegalStateException(
                                "Unsupported browser backend: " + BrowserRuntime.detectBackend());
                }
            } catch (LinkageError e) {
                log.log(Level.WARNING, "Embedded browser is unavailable; news panel disabled", e);
                return new MissingBrowserView(parentComponent);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "Embedded browser failed to initialize; news panel disabled", e);
                return new MissingBrowserView(parentComponent);
            }
        }
    }

    private static final class MissingBrowserView implements BrowserView {

        private final Component parentComponent;
        private final JPanel panel = new JPanel(new GridBagLayout());

        private MissingBrowserView(Component parentComponent) {
            this.parentComponent = parentComponent;
        }

        @Override
        public Component getComponent() {
            return panel;
        }

        @Override
        public void setBrowserBorder(Border border) {
            panel.setBorder(border);
        }

        @Override
        public void setDarkTheme(boolean darkTheme) {
            SwingHelper.applyTableBackground(panel);
        }

        @Override
        public void load(URL url) {
            showFallback();
        }

        @Override
        public void loadHtml(String html) {
            showFallback();
        }

        @Override
        public void disposeBrowser() {
        }

        private void showFallback() {
            panel.removeAll();
            JPanel content = BrowserFallbackPanels.buildUnavailablePanel(parentComponent);
            SwingHelper.applyTableBackground(content);
            panel.add(content);
            SwingHelper.applyTableBackground(panel);
            panel.revalidate();
            panel.repaint();
        }
    }
}
