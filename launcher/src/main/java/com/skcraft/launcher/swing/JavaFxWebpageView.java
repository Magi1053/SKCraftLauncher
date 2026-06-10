/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.swing;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.concurrent.Worker;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.web.PopupFeatures;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.util.Callback;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.net.MalformedURLException;
import java.net.URL;

final class JavaFxWebpageView extends JPanel implements WebpagePanel.BrowserView {

    private final Component parentComponent;
    private final JFXPanel browserPanel = new JFXPanel();
    private final JLayeredPane layeredPane = new JLayeredPane();
    private final JProgressBar progressBar = new JProgressBar();
    private final JPanel errorPanel = new JPanel(new GridBagLayout());

    private WebEngine webEngine;
    private URL url;
    private String html;
    private boolean darkTheme;
    private Border browserBorder;

    JavaFxWebpageView(Component parentComponent) {
        this.parentComponent = parentComponent;

        setLayout(new BorderLayout());

        layeredPane.setLayout(new WebpageLayoutManager());
        layeredPane.add(browserPanel, 1);

        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);
        layeredPane.add(progressBar, 2);

        errorPanel.setVisible(false);
        layeredPane.add(errorPanel, 3);

        add(layeredPane, BorderLayout.CENTER);

        Platform.setImplicitExit(false);
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                initWebView();
                loadPendingContent();
            }
        });
    }

    @Override
    public Component getComponent() {
        return this;
    }

    @Override
    public void setBrowserBorder(Border border) {
        browserBorder = border;
        browserPanel.setBorder(border);
    }

    @Override
    public void setDarkTheme(boolean darkTheme) {
        this.darkTheme = darkTheme;
        runOnFxThread(new Runnable() {
            @Override
            public void run() {
                applyPreferredColorScheme();
            }
        });
    }

    @Override
    public void load(URL url) {
        this.url = url;
        this.html = null;

        runOnFxThread(new Runnable() {
            @Override
            public void run() {
                loadPendingContent();
            }
        });
    }

    @Override
    public void loadHtml(String html) {
        this.url = null;
        this.html = html;

        runOnFxThread(new Runnable() {
            @Override
            public void run() {
                loadPendingContent();
            }
        });
    }

    private void initWebView() {
        final WebView webView = new WebView();
        webEngine = webView.getEngine();
        webEngine.setJavaScriptEnabled(true);
        URL stylesheet = JavaFxWebpageView.class.getResource("webview.css");
        if (stylesheet != null) {
            webEngine.setUserStyleSheetLocation(stylesheet.toExternalForm());
        }
        webEngine.setCreatePopupHandler(new Callback<PopupFeatures, WebEngine>() {
            @Override
            public WebEngine call(PopupFeatures popupFeatures) {
                WebEngine popupEngine = new WebEngine();
                popupEngine.locationProperty().addListener(new ChangeListener<String>() {
                    @Override
                    public void changed(ObservableValue<? extends String> observable, String oldValue, String newValue) {
                        openExternalUrl(newValue);
                    }
                });
                return popupEngine;
            }
        });

        webEngine.getLoadWorker().stateProperty().addListener(new ChangeListener<Worker.State>() {
            @Override
            public void changed(ObservableValue<? extends Worker.State> observable, Worker.State oldValue, Worker.State newValue) {
                handleLoadState(newValue);
            }
        });

        browserPanel.setScene(new Scene(webView));
    }

    private void loadPendingContent() {
        if (webEngine == null) {
            return;
        }

        hideError();

        if (html != null) {
            webEngine.loadContent(html, "text/html");
        } else if (url != null) {
            webEngine.load(url.toExternalForm());
        }
    }

    private void handleLoadState(Worker.State state) {
        if (state == Worker.State.RUNNING || state == Worker.State.SCHEDULED) {
            setProgressVisible(true);
        } else {
            setProgressVisible(false);
        }

        if (state == Worker.State.FAILED) {
            Throwable exception = webEngine.getLoadWorker().getException();
            String message = "Failed to load page";
            if (exception != null && exception.getMessage() != null) {
                message += ": " + exception.getMessage();
            }
            showError(message);
        } else if (state == Worker.State.SUCCEEDED) {
            hideError();
            applyPreferredColorScheme();
        }
    }

    private void applyPreferredColorScheme() {
        if (webEngine == null) {
            return;
        }

        String preferredScheme = darkTheme ? "dark" : "light";
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
                """.formatted(preferredScheme);

        try {
            webEngine.executeScript(script);
        } catch (RuntimeException ignored) {
        }
    }

    private static void runOnFxThread(Runnable action) {
        Platform.runLater(action);
    }

    private void setProgressVisible(final boolean visible) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                progressBar.setVisible(visible);
            }
        });
    }

    private void showError(final String message) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                errorPanel.removeAll();

                JPanel content = new JPanel(new MigLayout("insets 12, wrap 1", "[center]", "[]unrel[]"));
                content.add(new JLabel(message));

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

                errorPanel.add(content);
                errorPanel.setBorder(browserBorder);
                errorPanel.setVisible(true);
                errorPanel.revalidate();
                errorPanel.repaint();
            }
        });
    }

    private void hideError() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                errorPanel.setVisible(false);
            }
        });
    }

    private void openExternalUrl(String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }

        try {
            final URL popupUrl = new URL(value);
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    SwingHelper.openURL(popupUrl, parentComponent);
                }
            });
        } catch (MalformedURLException ignored) {
        }
    }
}
