/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.browser.mac;

import ca.weblite.webview.WebView;
import ca.weblite.webview.swing.WebViewComponent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skcraft.launcher.browser.BrowserFallbackPanels;
import com.skcraft.launcher.browser.BrowserView;
import com.skcraft.launcher.swing.SwingHelper;
import lombok.extern.java.Log;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.logging.Level;

/**
 * macOS embedded browser backed by the system WKWebView.
 */
@Log
public final class MacWkWebpageView extends JPanel implements BrowserView {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OPEN_EXTERNAL_CALLBACK = "launcherOpenExternal";
    private static final int ATTACH_CHECK_ATTEMPTS = 20;
    private static final int ATTACH_CHECK_DELAY_MS = 500;
    private static final String EXTERNAL_LINK_SCRIPT =
            "(function() {" +
            "document.addEventListener('click', function(event) {" +
            "var link = event.target;" +
            "while (link && link.tagName !== 'A') { link = link.parentElement; }" +
            "if (!link || !link.href) { return; }" +
            "var target = new URL(link.href, document.baseURI);" +
            "if (target.protocol !== 'http:' && target.protocol !== 'https:') { return; }" +
            "var current = window.location.href.split('#')[0];" +
            "if (target.href.split('#')[0] === current) { return; }" +
            "event.preventDefault();" +
            "window." + OPEN_EXTERNAL_CALLBACK + "(target.href);" +
            "}, true);" +
            "})();";

    private final Component parentComponent;

    private WebViewComponent webView;
    private Timer attachCheckTimer;
    private URL url;
    private String html;
    private boolean darkTheme;
    private boolean browserUnavailable;
    private boolean browserAttached;

    public MacWkWebpageView(Component parentComponent) {
        this.parentComponent = parentComponent;
        setLayout(new BorderLayout());
        applyBackground();
    }

    @Override
    public Component getComponent() {
        return this;
    }

    @Override
    public void setBrowserBorder(Border border) {
        setBorder(border);
    }

    @Override
    public void setDarkTheme(boolean darkTheme) {
        this.darkTheme = darkTheme;
        applyBackground();

        WebViewComponent current = webView;
        if (current != null) {
            current.eval(colorSchemeScript(darkTheme));
        }
    }

    @Override
    public void load(URL url) {
        this.url = url;
        this.html = null;

        WebViewComponent current = webView;
        if (current != null && browserAttached && url != null) {
            current.setUrl(url.toExternalForm());
        }
    }

    @Override
    public void loadHtml(String html) {
        this.url = null;
        this.html = html;

        WebViewComponent current = webView;
        if (current != null && browserAttached && html != null) {
            current.setUrl(htmlDataUrl(html));
        }
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (webView == null && !browserUnavailable) {
            createBrowser();
        }
    }

    @Override
    public void removeNotify() {
        disposeBrowser();
        super.removeNotify();
    }

    @Override
    public void disposeBrowser() {
        if (attachCheckTimer != null) {
            attachCheckTimer.stop();
            attachCheckTimer = null;
        }

        WebViewComponent current = webView;
        webView = null;
        browserAttached = false;
        if (current != null) {
            current.dispose();
            remove(current);
        }
    }

    private void createBrowser() {
        WebViewComponent component = null;
        try {
            component = WebViewComponent.create(WebViewComponent.Mode.HEAVYWEIGHT);
            component.addOnBeforeLoad(EXTERNAL_LINK_SCRIPT);
            component.addOnBeforeLoad(colorSchemeScript(darkTheme));
            component.addJavascriptCallback(OPEN_EXTERNAL_CALLBACK, this::openExternalUrl);

            component.setUrl("about:blank");

            webView = component;
            add(component, BorderLayout.CENTER);
            scheduleAttachCheck(component, ATTACH_CHECK_ATTEMPTS);
            revalidate();
            repaint();
        } catch (LinkageError | RuntimeException e) {
            webView = null;
            if (component != null) {
                try {
                    component.dispose();
                } catch (LinkageError | RuntimeException ignored) {
                }
                remove(component);
            }
            log.log(Level.WARNING, "WKWebView failed to initialize", e);
            showBrowserUnavailable();
        }
    }

    private void scheduleAttachCheck(final WebViewComponent component, final int attemptsRemaining) {
        Timer timer = new Timer(ATTACH_CHECK_DELAY_MS, e -> {
            attachCheckTimer = null;
            if (component != webView) {
                return;
            }

            try {
                component.evalAsync("return true;").whenComplete((result, failure) -> {
                    if (component != webView) {
                        return;
                    }
                    if (failure == null) {
                        browserAttached = true;
                        loadPendingContent(component);
                        return;
                    }
                    if (attemptsRemaining > 1) {
                        scheduleAttachCheck(component, attemptsRemaining - 1);
                    } else {
                        log.log(Level.WARNING, "WKWebView did not attach within 10 seconds", failure);
                        disposeBrowser();
                        showBrowserUnavailable();
                    }
                });
            } catch (RuntimeException e1) {
                if (attemptsRemaining > 1) {
                    scheduleAttachCheck(component, attemptsRemaining - 1);
                } else {
                    log.log(Level.WARNING, "WKWebView failed to attach", e1);
                    disposeBrowser();
                    showBrowserUnavailable();
                }
            }
        });
        timer.setRepeats(false);
        attachCheckTimer = timer;
        timer.start();
    }

    private void loadPendingContent(WebViewComponent component) {
        if (component != webView) {
            return;
        }
        if (html != null) {
            component.setUrl(htmlDataUrl(html));
        } else if (url != null) {
            component.setUrl(url.toExternalForm());
        }
    }

    private void openExternalUrl(String argumentsJson) {
        try {
            String[] arguments = JSON.readValue(argumentsJson, String[].class);
            if (arguments.length == 0 || arguments[0] == null || arguments[0].trim().isEmpty()) {
                return;
            }

            URL externalUrl = new URL(arguments[0]);
            SwingUtilities.invokeLater(() -> SwingHelper.openURL(externalUrl, parentComponent));
        } catch (IOException e) {
            log.log(Level.FINE, "Ignoring malformed WKWebView navigation callback", e);
        }
    }

    private void showBrowserUnavailable() {
        browserUnavailable = true;
        removeAll();
        add(BrowserFallbackPanels.buildUnavailablePanel(parentComponent), BorderLayout.CENTER);
        applyBackground();
        revalidate();
        repaint();
    }

    private void applyBackground() {
        SwingHelper.applyTableBackground(this);
    }

    private static String htmlDataUrl(String html) {
        String encoded = Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8));
        return "data:text/html;charset=utf-8;base64," + encoded;
    }

    private static String colorSchemeScript(boolean darkTheme) {
        String scheme = darkTheme ? "dark" : "light";
        return "(function() {" +
                "var apply = function() {" +
                "if (document.documentElement) { document.documentElement.style.colorScheme = '" + scheme + "'; }" +
                "};" +
                "apply();" +
                "document.addEventListener('DOMContentLoaded', apply, {once:true});" +
                "})();";
    }
}
