/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.browser.weblite;

import ca.weblite.webview.swing.WebViewComponent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.formdev.flatlaf.FlatLaf;
import com.skcraft.launcher.browser.BrowserFallbackPanels;
import com.skcraft.launcher.browser.BrowserView;
import com.skcraft.launcher.browser.platform.BrowserPlatform;
import com.skcraft.launcher.swing.SwingHelper;
import com.skcraft.launcher.util.SharedLocale;
import lombok.extern.java.Log;
import net.miginfocom.swing.MigLayout;

import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.Border;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.logging.Level;

/**
 * Cross-platform embedded browser backed by weblite and the host web engine.
 */
@Log
public final class WebliteWebpageView extends JPanel implements BrowserView {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BrowserPlatform PLATFORM = BrowserPlatform.current();
    private static final String OPEN_EXTERNAL_CALLBACK = "launcherOpenExternal";
    private static final int CONTENT_READY_CHECK_ATTEMPTS = 100;
    private static final int CONTENT_READY_CHECK_DELAY_MS = 50;
    private static final int CONTENT_REVEAL_DELAY_MS = 50;

    private static final String BROWSER_CARD = "browser";
    private static final String ERROR_CARD = "error";

    private static final String EXTERNAL_LINK_SCRIPT = "(() => {" +
            "const HIDDEN = 'data-skcraft-external-href';" +
            "const pageUrl = () => location.href.split('#')[0];" +
            "const suppress = link => {" +
            "if (!(link instanceof HTMLAnchorElement) || !link.hasAttribute('href')) return;" +
            "let target;" +
            "try { target = new URL(link.getAttribute('href'), document.baseURI); }" +
            "catch { return; }" +
            "const external = (target.protocol === 'http:' || target.protocol === 'https:') " +
            "&& target.href.split('#')[0] !== pageUrl();" +
            "if (!external) { link.removeAttribute(HIDDEN); return; }" +
            "link.setAttribute(HIDDEN, target.href);" +
            "link.removeAttribute('href');" +
            "link.style.cursor ||= 'pointer';};" +
            "const scan = root => {" +
            "if (!(root instanceof Element)) return;" +
            "if (root.matches('a[href]')) suppress(root);" +
            "root.querySelectorAll('a[href]').forEach(suppress);};" +
            "new MutationObserver(records => {" +
            "for (const {type, target, addedNodes} of records) {" +
            "if (type === 'attributes') suppress(target);" +
            "else addedNodes.forEach(scan);}" +
            "}).observe(document, {childList: true, subtree: true, attributes: true, attributeFilter: ['href']});" +
            "scan(document.documentElement);" +
            "document.addEventListener('click', event => {" +
            "const link = event.target.closest?.('a');" +
            "const href = link?.getAttribute(HIDDEN) || link?.getAttribute('href');" +
            "if (!href) return;" +
            "const target = new URL(href, document.baseURI);" +
            "if (target.protocol !== 'http:' && target.protocol !== 'https:') return;" +
            "if (target.href.split('#')[0] === pageUrl()) return;" +
            "event.preventDefault();" +
            "window." + OPEN_EXTERNAL_CALLBACK + "(target.href);" +
            "}, true);})();";

    private final Component parentComponent;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cardPanel = new JPanel(cardLayout);
    private final JLayeredPane browserStack = new JLayeredPane() {
        @Override
        public void doLayout() {
            layoutBrowserStack();
        }
    };
    private final JPanel webViewHolder = new JPanel(new BorderLayout());
    private final JPanel loadingOverlay = new JPanel();
    private final JPanel errorPanel = new JPanel(new GridBagLayout());

    private WebViewComponent webView;
    private Timer contentReadyTimer;
    private URL url;
    private String html;
    private boolean darkTheme;
    private boolean browserUnavailable;
    private long contentNavigation;

    public WebliteWebpageView(Component parentComponent) {
        this.parentComponent = parentComponent;
        this.darkTheme = FlatLaf.isLafDark();
        setLayout(new BorderLayout());

        webViewHolder.setOpaque(false);

        loadingOverlay.setLayout(new MigLayout("fill, align center center", "[100!]", "[]"));
        loadingOverlay.setOpaque(true);
        JProgressBar progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        loadingOverlay.add(progressBar, "w 100!, h 16!");

        browserStack.add(webViewHolder, JLayeredPane.DEFAULT_LAYER);
        browserStack.add(loadingOverlay, JLayeredPane.PALETTE_LAYER);

        cardPanel.add(browserStack, BROWSER_CARD);
        cardPanel.add(errorPanel, ERROR_CARD);
        cardLayout.show(cardPanel, BROWSER_CARD);

        add(cardPanel, BorderLayout.CENTER);
        applyBackground();
        showLoading();
    }

    @Override
    public Component getComponent() {
        return this;
    }

    @Override
    public void setBrowserBorder(Border border) {
        browserStack.setBorder(border);
        errorPanel.setBorder(border);
    }

    @Override
    public void setDarkTheme(boolean darkTheme) {
        boolean changed = this.darkTheme != darkTheme;
        this.darkTheme = darkTheme;
        applyBackground();

        WebViewComponent current = webView;
        if (current == null) {
            return;
        }
        applyBrowserBackground(current);
        if (changed) {
            PLATFORM.applyPreferredColorScheme(current, darkTheme);
        }
    }

    @Override
    public void load(URL url) {
        if (browserUnavailable) {
            return;
        }

        this.url = url;
        this.html = null;

        WebViewComponent current = webView;
        if (current != null && url != null) {
            navigate(current, url.toExternalForm());
        } else {
            showLoading();
        }
    }

    @Override
    public void loadHtml(String html) {
        if (browserUnavailable) {
            return;
        }

        this.url = null;
        this.html = html;

        WebViewComponent current = webView;
        if (current != null && html != null) {
            navigate(current, htmlDataUrl(html));
        } else {
            showLoading();
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
        if (contentReadyTimer != null) {
            contentReadyTimer.stop();
            contentReadyTimer = null;
        }

        WebViewComponent current = webView;
        webView = null;
        contentNavigation++;
        hideLoading();
        if (current != null) {
            PLATFORM.stopBoundsFix(current);
            current.dispose();
            webViewHolder.remove(current);
        }
    }

    private void createBrowser() {
        showCard(BROWSER_CARD);
        showLoading();

        if (!PLATFORM.isHostRuntimeAvailable()) {
            log.warning("Host browser runtime is unavailable");
            showBrowserUnavailable();
            return;
        }

        WebViewComponent component = null;
        try {
            component = WebViewComponent.create();
            component.setMinimumSize(new Dimension(0, 0));
            component.setPreferredSize(new Dimension(0, 0));
            applyBrowserBackground(component);
            component.addOnBeforeLoad(EXTERNAL_LINK_SCRIPT);
            component.addJavascriptCallback(OPEN_EXTERNAL_CALLBACK, this::openExternalUrl);

            webView = component;
            webViewHolder.add(component, BorderLayout.CENTER);
            PLATFORM.applyPreferredColorScheme(component, darkTheme);
            PLATFORM.installBoundsFix(component);
            PLATFORM.setContentVisible(component, false);
            syncBrowserStackBounds();
            loadPendingContent(component);
            webViewHolder.revalidate();
            webViewHolder.repaint();
        } catch (LinkageError | RuntimeException e) {
            webView = null;
            if (component != null) {
                PLATFORM.stopBoundsFix(component);
                try {
                    component.dispose();
                } catch (LinkageError | RuntimeException ignored) {
                }
                webViewHolder.remove(component);
            }
            log.log(Level.WARNING, "Weblite browser failed to initialize", e);
            showBrowserUnavailable();
        }
    }

    private void loadPendingContent(WebViewComponent component) {
        if (component != webView) {
            return;
        }
        if (html != null) {
            navigate(component, htmlDataUrl(html));
        } else if (url != null) {
            navigate(component, url.toExternalForm());
        } else {
            revealBrowser(component);
        }
    }

    private void navigate(WebViewComponent component, String target) {
        PLATFORM.setContentVisible(component, false);
        showCard(BROWSER_CARD);
        showLoading();
        long navigation = ++contentNavigation;
        component.setUrl(target);
        scheduleContentReadyCheck(component, navigation, CONTENT_READY_CHECK_ATTEMPTS);
    }

    private void scheduleContentReadyCheck(
            WebViewComponent component, long navigation, int attemptsRemaining) {
        if (contentReadyTimer != null) {
            contentReadyTimer.stop();
        }

        Timer timer = new Timer(CONTENT_READY_CHECK_DELAY_MS, event -> {
            contentReadyTimer = null;
            if (component != webView || navigation != contentNavigation) {
                return;
            }

            try {
                // Chromium/WebView2 "site can't be reached" uses chrome-error:// and a
                // full-window interstitial that dwarfs the news panel; swap to Swing.
                component.evalAsync(
                        "return (() => {" +
                                "const href = String(location.href || '');" +
                                "if (!href || href === 'about:blank' " +
                                "|| document.readyState === 'loading') return 'loading';" +
                                "if (href.indexOf('chrome-error:') === 0 " +
                                "|| href.indexOf('chromewebdata') >= 0) return 'error';" +
                                "if (document.getElementById('main-frame-error') " +
                                "|| document.querySelector('.interstitial-wrapper') " +
                                "|| (document.body && document.body.classList.contains('neterror'))) " +
                                "return 'error';" +
                                "return 'ready';" +
                                "})()")
                        .whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> handleContentReadyResult(
                                component,
                                navigation,
                                attemptsRemaining,
                                result,
                                failure)));
            } catch (RuntimeException e) {
                handleContentReadyResult(
                        component, navigation, attemptsRemaining, null, e);
            }
        });
        timer.setRepeats(false);
        contentReadyTimer = timer;
        timer.start();
    }

    private void handleContentReadyResult(
            WebViewComponent component,
            long navigation,
            int attemptsRemaining,
            String result,
            Throwable failure) {
        if (component != webView || navigation != contentNavigation) {
            return;
        }
        String status = normalizeProbeResult(result);
        if (failure == null && "error".equals(status)) {
            showLoadFailed();
        } else if (failure == null && "ready".equals(status)) {
            scheduleBrowserReveal(component, navigation);
        } else if (attemptsRemaining > 1) {
            scheduleContentReadyCheck(component, navigation, attemptsRemaining - 1);
        } else {
            log.log(Level.FINE, "Weblite content readiness probe timed out", failure);
            revealBrowser(component);
        }
    }

    private void scheduleBrowserReveal(WebViewComponent component, long navigation) {
        Timer timer = new Timer(CONTENT_REVEAL_DELAY_MS, event -> {
            contentReadyTimer = null;
            if (component == webView && navigation == contentNavigation) {
                revealBrowser(component);
            }
        });
        timer.setRepeats(false);
        contentReadyTimer = timer;
        timer.start();
    }

    private void revealBrowser(WebViewComponent component) {
        if (component != webView) {
            return;
        }
        // Peer is ready after content load; ensure scheme matches launcher theme.
        if (url != null || html != null) {
            PLATFORM.applyPreferredColorScheme(component, darkTheme);
        }
        hideLoading();
        PLATFORM.setContentVisible(component, true);
        showCard(BROWSER_CARD);
    }

    private static String normalizeProbeResult(String result) {
        if (result == null) {
            return "";
        }
        return result.replace("\"", "").trim().toLowerCase();
    }

    private void openExternalUrl(String argumentsJson) {
        try {
            String href = extractCallbackUrl(argumentsJson);
            if (href == null || href.isEmpty()) {
                return;
            }

            URL externalUrl = new URL(href);
            SwingUtilities.invokeLater(() -> SwingHelper.openURL(externalUrl, parentComponent));
        } catch (IOException e) {
            log.log(Level.WARNING, "Ignoring malformed weblite navigation callback", e);
        }
    }

    /**
     * weblite embed bindings post {@code {"name","seq","args":[...]}}; older /
     * standalone binds may pass a bare args array or a single string.
     */
    private static String extractCallbackUrl(String argumentsJson) throws IOException {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }

        JsonNode root = JSON.readTree(argumentsJson);
        JsonNode args = root;
        if (root != null && root.isObject()) {
            args = root.get("args");
        }
        if (args != null && args.isArray() && args.size() > 0 && !args.get(0).isNull()) {
            String href = args.get(0).asText(null);
            return href == null ? null : href.trim();
        }
        if (root != null && root.isTextual()) {
            return root.asText().trim();
        }
        return null;
    }

    private void showLoading() {
        loadingOverlay.setVisible(true);
        syncBrowserStackBounds();
        loadingOverlay.revalidate();
        loadingOverlay.repaint();
    }

    private void hideLoading() {
        loadingOverlay.setVisible(false);
        loadingOverlay.revalidate();
        loadingOverlay.repaint();
    }

    private void showBrowserUnavailable() {
        browserUnavailable = true;
        hideLoading();
        errorPanel.removeAll();
        errorPanel.add(BrowserFallbackPanels.buildUnavailablePanel(parentComponent));
        applyBackground();
        showCard(ERROR_CARD);
        errorPanel.revalidate();
        errorPanel.repaint();
    }

    private void showLoadFailed() {
        WebViewComponent current = webView;
        if (current != null) {
            PLATFORM.setContentVisible(current, false);
        }
        hideLoading();
        errorPanel.removeAll();
        errorPanel.add(BrowserFallbackPanels.buildErrorPanel(
                SharedLocale.tr("news.panel.loadFailed")));
        applyBackground();
        showCard(ERROR_CARD);
        errorPanel.revalidate();
        errorPanel.repaint();
    }

    private void showCard(String cardName) {
        if (browserUnavailable && !ERROR_CARD.equals(cardName)) {
            return;
        }
        cardLayout.show(cardPanel, cardName);
        cardPanel.revalidate();
        cardPanel.repaint();
        if (BROWSER_CARD.equals(cardName)) {
            syncBrowserStackBounds();
        }
    }

    private void syncBrowserStackBounds() {
        layoutBrowserStack();
        browserStack.revalidate();
        browserStack.repaint();
    }

    private void layoutBrowserStack() {
        Dimension size = browserStack.getSize();
        int width = Math.max(size.width, 0);
        int height = Math.max(size.height, 0);
        webViewHolder.setBounds(0, 0, width, height);
        loadingOverlay.setBounds(0, 0, width, height);
    }

    private void applyBackground() {
        SwingHelper.applyTableBackground(
                this, cardPanel, browserStack, webViewHolder, errorPanel, loadingOverlay);
    }

    private void applyBrowserBackground(Component component) {
        component.setBackground(getBackground());
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                applyBrowserBackground(child);
            }
        }
    }

    private static String htmlDataUrl(String html) {
        String encoded = Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8));
        return "data:text/html;charset=utf-8;base64," + encoded;
    }
}
