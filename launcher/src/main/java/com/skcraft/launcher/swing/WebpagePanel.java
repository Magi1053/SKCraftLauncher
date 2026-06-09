/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.swing;

import lombok.extern.java.Log;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.net.URL;
import java.util.logging.Level;

@Log
public final class WebpagePanel extends JPanel {

    private URL url;
    private String html;
    private boolean activated;
    private Border browserBorder = createDefaultBrowserBorder();
    private BrowserView browserView;

    public static WebpagePanel forURL(URL url) {
        return new WebpagePanel(url);
    }

    public static WebpagePanel forHTML(String html) {
        return new WebpagePanel(html);
    }

    private WebpagePanel(URL url) {
        this.url = url;

        setLayout(new BorderLayout());
        activateBrowser();
    }

    private WebpagePanel(String html) {
        this.html = html;

        setLayout(new BorderLayout());
        activateBrowser();
    }

    public WebpagePanel() {
        setLayout(new BorderLayout());
        activateBrowser();
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

    private static Border createDefaultBrowserBorder() {
        Border border = UIManager.getBorder("ScrollPane.border");
        return border != null ? border : BorderFactory.createEtchedBorder();
    }

    /**
     * Browse to a URL.
     *
     * @param url the URL
     * @param onlyChanged true to only browse if the last URL was different
     * @return true if only the URL was changed
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

        browserView = createBrowserView();
        browserView.setBrowserBorder(browserBorder);
        add(browserView.getComponent(), BorderLayout.CENTER);
        SwingHelper.removeOpaqueness(this);

        revalidate();
        repaint();

        if (html != null) {
            browserView.loadHtml(html);
        } else if (url != null) {
            browserView.load(url);
        }
    }

    private BrowserView createBrowserView() {
        try {
            return new JavaFxWebpageView(this);
        } catch (LinkageError e) {
            log.log(Level.WARNING, "JavaFX is not available; using external-browser news fallback", e);
            return new MissingJavaFxBrowserView(this);
        }
    }

    interface BrowserView {
        Component getComponent();

        void setBrowserBorder(Border border);

        void load(URL url);

        void loadHtml(String html);
    }

    private static final class MissingJavaFxBrowserView implements BrowserView {
        private final Component parentComponent;
        private final JPanel panel = new JPanel(new GridBagLayout());
        private URL url;

        private MissingJavaFxBrowserView(Component parentComponent) {
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
        public void load(URL url) {
            this.url = url;
            showFallback("JavaFX WebView is not available in this Java runtime.");
        }

        @Override
        public void loadHtml(String html) {
            this.url = null;
            showFallback("JavaFX WebView is not available in this Java runtime.");
        }

        private void showFallback(String message) {
            panel.removeAll();

            JPanel content = new JPanel(new MigLayout("insets 12, wrap 1", "[center]", "[]unrel[]"));
            content.add(new JLabel(message), "wrap");

            JButton openButton = new JButton("Open news in browser");
            openButton.setEnabled(url != null);
            openButton.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (url != null) {
                        SwingHelper.openURL(url, parentComponent);
                    }
                }
            });
            content.add(openButton);

            panel.add(content);
            panel.revalidate();
            panel.repaint();
        }
    }

}
