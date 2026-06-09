/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.dialog;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.skcraft.concurrency.SettableProgress;
import com.skcraft.launcher.Launcher;
import com.skcraft.launcher.auth.MicrosoftLoginService;
import com.skcraft.launcher.auth.Session;
import com.skcraft.launcher.swing.SwingHelper;
import com.skcraft.launcher.util.QrCodes;
import com.skcraft.launcher.util.SharedLocale;
import com.skcraft.launcher.util.SwingExecutor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import static com.skcraft.launcher.util.SharedLocale.tr;

/**
 * Modal dialog driving the Microsoft device-code sign in flow.
 *
 * <p>
 * Fetches a device code, presents step-by-step instructions, polls for
 * completion,
 * and surfaces a fallback path for the redirect-based browser flow.
 * </p>
 */
public class MicrosoftLoginDialog extends JDialog {

	private static final int QR_SIZE = 144;
	private static final int QR_PADDING = 2;
	private static final int CODE_FIELD_FONT_SIZE = 32;
	private static final int COPY_ICON_SIZE = 18;
	private static final Icon COPY_ICON = createCopyIcon(COPY_ICON_SIZE);
	private static final Icon COPIED_ICON = createCopiedIcon(COPY_ICON_SIZE);

	private final Launcher launcher;
	private final MicrosoftLoginService.DeviceCodeDetails details;
	private final String authUrl;

	private final JButton openBrowserButton = new JButton(tr("login.microsoft.device.openBrowser"));
	private final JButton copyCodeButton = createCopyIconButton();
	private final JButton fallbackButton = new JButton(tr("login.microsoft.device.useBrowserFallback"));
	private final JButton cancelButton = new JButton(tr("button.cancel"));
	private final JTextField codeField;
	private final JLabel statusLabel = new JLabel(tr("login.microsoft.device.awaiting"));
	private final JLabel countdownLabel = new JLabel(" ");
	private final JLabel qrLabel = new JLabel();
	private final QrSpinnerPanel qrSpinnerPanel = new QrSpinnerPanel(QR_SIZE);
	private final JPanel qrContentPanel = new JPanel(new CardLayout());
	private JPanel qrOptionPanel;
	private final JLabel qrHintLabel = new JLabel(tr("login.microsoft.device.qrHint"));
	private final JLabel browserHintLabel = new JLabel();
	private final JSeparator optionDivider = new JSeparator(SwingConstants.VERTICAL);
	private final JProgressBar pollIndicator = new JProgressBar();

	private final Timer countdownTimer;
	private Timer copyResetTimer;
	private ListenableFuture<Session> pollFuture;

	@Getter
	private Outcome outcome = Outcome.cancelled();
	private boolean alreadyOpenedBrowser;
	private boolean codeExpired;

	private MicrosoftLoginDialog(Window owner, Launcher launcher, MicrosoftLoginService.DeviceCodeDetails details) {
		super(owner, tr("login.microsoft.device.dialogTitle"), ModalityType.DOCUMENT_MODAL);
		this.launcher = launcher;
		this.details = details;
		this.authUrl = details.getVerificationUriComplete() != null
				? details.getVerificationUriComplete()
				: details.getVerificationUri();
		this.codeField = new JTextField(details.getUserCode());
		this.countdownTimer = new Timer(1000, ev -> updateCountdown());
		this.countdownTimer.setInitialDelay(0);

		initComponents();
		updateCountdown();

		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowOpened(WindowEvent event) {
				countdownTimer.start();
				if (authUrl != null && !authUrl.isEmpty()) {
					qrSpinnerPanel.start();
				}
				startPolling();
				loadQrCode();
			}

			@Override
			public void windowClosing(WindowEvent event) {
				handleCancel();
			}
		});

		pack();
		setMinimumSize(getSize());
		setResizable(false);
		setLocationRelativeTo(owner);
	}

	private void initComponents() {
		JPanel content = new JPanel(new MigLayout(
				"insets 18 22 16 22, wrap 1, fillx, hidemode 3",
				"[grow, fill]"));

		JLabel titleLabel = new JLabel(tr("login.microsoft.device.title"));
		titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, titleLabel.getFont().getSize2D() + 4f));

		JLabel subtitleLabel = new JLabel(tr("login.microsoft.device.subtitle"));
		subtitleLabel.setForeground(SwingHelper.uiColor("Label.disabledForeground", Color.DARK_GRAY));

		content.add(titleLabel, "gapbottom 2");
		content.add(subtitleLabel, "gapbottom 14");
		content.add(new JSeparator(), "growx, gapbottom 14");

		content.add(buildStepLabel(1, tr("login.microsoft.device.step1")), "gapbottom 8");
		content.add(buildOptionsPanel(), "growx, gapbottom 18");

		content.add(buildStepLabel(2, tr("login.microsoft.device.step2")), "gapbottom 6");

		codeField.setEditable(false);
		codeField.setHorizontalAlignment(SwingConstants.CENTER);
		codeField.setFont(new Font(Font.MONOSPACED, Font.BOLD, CODE_FIELD_FONT_SIZE));
		codeField.setBorder(new EmptyBorder(10, 16, 10, 8));

		Color codeBorderColor = SwingHelper.uiColor("Component.borderColor", new Color(160, 160, 160));
		JPanel codeBox = new JPanel(new BorderLayout(0, 0));
		Color codeBackground = codeField.getBackground();
		codeBox.setBackground(codeBackground);
		codeBox.setBorder(BorderFactory.createLineBorder(codeBorderColor));
		copyCodeButton.setBackground(codeBackground);
		codeBox.add(codeField, BorderLayout.CENTER);
		codeBox.add(copyCodeButton, BorderLayout.EAST);
		content.add(codeBox, "growx, gapbottom 14");

		content.add(new JSeparator(), "growx, gapbottom 12");

		pollIndicator.setIndeterminate(true);
		pollIndicator.setPreferredSize(new Dimension(18, 18));
		statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD));
		countdownLabel.setForeground(SwingHelper.uiColor("Label.disabledForeground", Color.DARK_GRAY));

		JPanel statusRow = new JPanel(new MigLayout("insets 0, gap 10", "[][grow, fill][]"));
		statusRow.add(pollIndicator, "w 18!, h 18!");
		statusRow.add(statusLabel, "growx");
		statusRow.add(countdownLabel, "");
		content.add(statusRow, "growx");

		JPanel buttonBar = new JPanel(new MigLayout("insets 12 22 16 22, fillx", "[]push[]"));
		buttonBar.add(fallbackButton);
		buttonBar.add(cancelButton, "tag cancel");

		setLayout(new BorderLayout());
		add(content, BorderLayout.CENTER);
		add(buttonBar, BorderLayout.SOUTH);

		SwingHelper.styleDialogButton(openBrowserButton);
		SwingHelper.styleDialogButton(copyCodeButton);
		SwingHelper.styleDialogButton(fallbackButton);
		SwingHelper.styleDialogButton(cancelButton);
		SwingHelper.alignButtonSizes(fallbackButton, cancelButton);

		openBrowserButton.addActionListener(ev -> handleOpenBrowser());
		copyCodeButton.addActionListener(ev -> handleCopy());
		fallbackButton.setToolTipText(tr("login.microsoft.device.fallbackTooltip"));
		fallbackButton.addActionListener(ev -> handleFallback());
		cancelButton.addActionListener(ev -> handleCancel());

		getRootPane().setDefaultButton(openBrowserButton);
		getRootPane().registerKeyboardAction(ev -> handleCancel(),
				KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
				JComponent.WHEN_IN_FOCUSED_WINDOW);
	}

	private JLabel buildStepLabel(int number, String instruction) {
		String html = String.format("<html><b>%s</b> &nbsp;%s</html>",
				SwingHelper.htmlEscape(tr("login.microsoft.device.stepLabel", number)),
				SwingHelper.htmlEscape(instruction));
		return new JLabel(html);
	}

	private JPanel buildOptionsPanel() {
		Color mutedColor = SwingHelper.uiColor("Label.disabledForeground", Color.DARK_GRAY);

		openBrowserButton.setFont(openBrowserButton.getFont().deriveFont(Font.BOLD));
		openBrowserButton.setMargin(new Insets(8, 18, 8, 18));

		String verificationUri = details.getVerificationUri() != null ? details.getVerificationUri() : authUrl;
		browserHintLabel.setText(String.format("<html><div style='text-align:center'>%s</div></html>",
				SwingHelper.htmlEscape(tr("login.microsoft.device.browserHint", verificationUri))));
		browserHintLabel.setForeground(mutedColor);
		browserHintLabel.setHorizontalAlignment(SwingConstants.CENTER);

		int columnWidth = 220;
		JPanel browserOption = new JPanel(new MigLayout("insets 0, wrap 1", "[center, " + columnWidth + "!]",
				"[top]8[top]"));
		browserOption.add(openBrowserButton, "wmin 180, hmin 38");
		browserOption.add(browserHintLabel, "growx");

		qrHintLabel.setForeground(mutedColor);
		qrHintLabel.setHorizontalAlignment(SwingConstants.CENTER);
		qrLabel.setHorizontalAlignment(SwingConstants.CENTER);
		qrLabel.setVerticalAlignment(SwingConstants.CENTER);
		qrLabel.setOpaque(true);
		qrLabel.setBackground(Color.WHITE);
		qrLabel.setBorder(BorderFactory.createEmptyBorder(QR_PADDING, QR_PADDING, QR_PADDING, QR_PADDING));

		qrContentPanel.add(qrSpinnerPanel, "spinner");
		qrContentPanel.add(qrLabel, "qr");

		qrOptionPanel = new JPanel(new MigLayout("insets 0, wrap 1", "[center]", "[" + QR_SIZE + "!]10[top]"));
		qrOptionPanel.add(qrContentPanel, "w " + QR_SIZE + "!, h " + QR_SIZE + "!");
		qrOptionPanel.add(qrHintLabel, "growx");

		boolean qrExpected = authUrl != null && !authUrl.isEmpty();
		qrOptionPanel.setVisible(qrExpected);
		optionDivider.setVisible(qrExpected);

		JPanel options = new JPanel(new MigLayout("insets 0", "[center]20[]20[center]", "[center]"));
		options.add(browserOption, "aligny center");
		options.add(optionDivider, "growy, w 1!");
		options.add(qrOptionPanel, "aligny center");
		return options;
	}

	private void startPolling() {
		pollFuture = launcher.getExecutor().submit(() -> launcher.getMicrosoftLogin()
				.loginWithDeviceCode(details, () -> SwingUtilities.invokeLater(() -> {
					statusLabel.setText(tr("login.loggingInStatus"));
					countdownLabel.setText(" ");
					countdownTimer.stop();
				})));

		Futures.addCallback(pollFuture, new FutureCallback<Session>() {
			@Override
			public void onSuccess(Session session) {
				if (session == null) {
					handleCancel();
					return;
				}

				outcome = Outcome.success(session);
				closeDialog();
			}

			@Override
			public void onFailure(Throwable t) {
				if (codeExpired || pollFuture.isCancelled() || t instanceof CancellationException) {
					return;
				}

				closeDialog();
				SwingHelper.showErrorDialog(getOwner(), t.getLocalizedMessage(), tr("errorTitle"), t);
			}
		}, SwingExecutor.INSTANCE);
	}

	private void loadQrCode() {
		if (authUrl == null || authUrl.isEmpty()) {
			return;
		}

		SwingWorker<BufferedImage, Void> worker = new SwingWorker<BufferedImage, Void>() {
			@Override
			protected BufferedImage doInBackground() {
				return QrCodes.generate(authUrl, QR_SIZE - 2 * QR_PADDING);
			}

			@Override
			protected void done() {
				try {
					BufferedImage image = get();
					if (image != null) {
						qrLabel.setIcon(new ImageIcon(image));
						qrSpinnerPanel.stop();
						((CardLayout) qrContentPanel.getLayout()).show(qrContentPanel, "qr");
						pack();
					} else {
						hideQrOption();
					}
				} catch (InterruptedException | ExecutionException ignored) {
					hideQrOption();
				}
			}
		};
		worker.execute();
	}

	private void updateCountdown() {
		long remaining = details.getExpiresAt() - System.currentTimeMillis();
		if (remaining <= 0) {
			if (!codeExpired) {
				handleCodeExpired();
			}
			return;
		}

		long totalSeconds = remaining / 1000L;
		long minutes = totalSeconds / 60L;
		long seconds = totalSeconds % 60L;
		String formatted = String.format("%d:%02d", minutes, seconds);
		countdownLabel.setText(tr("login.microsoft.device.timeRemaining", formatted));
	}

	private void handleCodeExpired() {
		codeExpired = true;
		countdownTimer.stop();
		countdownLabel.setText(tr("login.microsoft.device.timeExpired"));
		countdownLabel.setForeground(SwingHelper.uiColor("Component.error.focusedBorderColor", Color.RED.darker()));
		statusLabel.setText(tr("login.microsoft.device.expiredStatus"));
		pollIndicator.setIndeterminate(false);
		pollIndicator.setVisible(false);
		if (pollFuture != null && !pollFuture.isDone()) {
			pollFuture.cancel(true);
		}
	}

	private void handleOpenBrowser() {
		SwingHelper.openURL(authUrl, this);
		if (!alreadyOpenedBrowser) {
			alreadyOpenedBrowser = true;
			openBrowserButton.setText(tr("login.microsoft.device.openBrowserAgain"));
			openBrowserButton.setFont(openBrowserButton.getFont().deriveFont(Font.PLAIN));
		}
	}

	private void handleCopy() {
		SwingHelper.setClipboard(details.getUserCode());

		copyCodeButton.setIcon(COPIED_ICON);
		copyCodeButton.setToolTipText(tr("login.microsoft.device.copied"));
		if (copyResetTimer != null && copyResetTimer.isRunning()) {
			copyResetTimer.stop();
		}
		copyResetTimer = new Timer(1600, ev -> {
			copyCodeButton.setIcon(COPY_ICON);
			copyCodeButton.setToolTipText(tr("login.microsoft.device.copy"));
		});
		copyResetTimer.setRepeats(false);
		copyResetTimer.start();
	}

	private void handleFallback() {
		outcome = Outcome.fallback();
		closeDialog();
	}

	private void handleCancel() {
		outcome = Outcome.cancelled();
		closeDialog();
	}

	private void hideQrOption() {
		qrSpinnerPanel.stop();
		if (qrOptionPanel != null) {
			qrOptionPanel.setVisible(false);
		}
		optionDivider.setVisible(false);
		pack();
	}

	private void closeDialog() {
		countdownTimer.stop();
		qrSpinnerPanel.stop();
		if (copyResetTimer != null) {
			copyResetTimer.stop();
		}
		if (pollFuture != null && !pollFuture.isDone()) {
			pollFuture.cancel(true);
		}
		dispose();
	}

	private static JButton createCopyIconButton() {
		JButton button = new JButton(COPY_ICON);
		button.setToolTipText(tr("login.microsoft.device.copy"));
		button.setFocusable(false);
		button.setFocusPainted(false);
		button.setContentAreaFilled(false);
		button.setBorderPainted(false);
		button.setOpaque(false);
		button.setMargin(new Insets(0, 0, 0, 0));
		button.setBorder(new EmptyBorder(0, 4, 0, 12));
		return button;
	}

	private static Icon createCopyIcon(int size) {
		return paintIcon(size, g -> {
			Color stroke = new Color(55, 55, 55);
			g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			int pad = Math.max(2, size / 9);
			int w = size - pad * 2 - 3;
			int h = size - pad * 2 - 3;
			int arc = 3;
			g.setColor(stroke);
			g.drawRoundRect(pad + 3, pad, w, h, arc, arc);
			g.setColor(new Color(240, 240, 240));
			g.fillRoundRect(pad, pad + 3, w, h, arc, arc);
			g.setColor(stroke);
			g.drawRoundRect(pad, pad + 3, w, h, arc, arc);
		});
	}

	private static Icon createCopiedIcon(int size) {
		return paintIcon(size, g -> {
			g.setColor(new Color(34, 139, 34));
			g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			Path2D check = new Path2D.Float();
			check.moveTo(size * 0.2, size * 0.52);
			check.lineTo(size * 0.42, size * 0.72);
			check.lineTo(size * 0.8, size * 0.32);
			g.draw(check);
		});
	}

	private static Icon paintIcon(int size, Consumer<Graphics2D> painter) {
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			painter.accept(g);
		} finally {
			g.dispose();
		}
		return new ImageIcon(image);
	}

	/**
	 * Run the full Microsoft device-code sign in flow as a modal dialog.
	 *
	 * <p>
	 * Fetches the device code (showing progress), presents the sign-in dialog,
	 * and waits for the user to finish or cancel. Always returns synchronously.
	 * </p>
	 *
	 * @param owner    parent window for modality and positioning
	 * @param launcher launcher providing the Microsoft login service
	 * @return outcome of the flow; never {@code null}
	 */
	public static Outcome showLogin(Window owner, Launcher launcher) {
		ListenableFuture<MicrosoftLoginService.DeviceCodeDetails> fetchFuture = launcher.getExecutor()
				.submit(() -> launcher.getMicrosoftLogin().requestDeviceCodeDetails());

		SettableProgress progress = new SettableProgress(tr("login.microsoft.device.starting"), -1);
		ProgressDialog.showProgress(owner, fetchFuture, progress, tr("login.microsoft.device.fetchingTitle"), tr("login.microsoft.device.starting"));

		MicrosoftLoginService.DeviceCodeDetails details;
		try {
			details = fetchFuture.get();
		} catch (CancellationException e) {
			return Outcome.cancelled();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Outcome.cancelled();
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			SwingHelper.showErrorDialog(owner, cause.getLocalizedMessage(), tr("errorTitle"), cause);
			return Outcome.cancelled();
		}

		if (details == null) {
			return Outcome.cancelled();
		}

		MicrosoftLoginDialog dialog = new MicrosoftLoginDialog(owner, launcher, details);
		dialog.setVisible(true);
		return dialog.outcome;
	}

	/**
	 * Spinner shown while the QR code is generated.
	 */
	private static final class QrSpinnerPanel extends JPanel {

		private static final Color TRACK = new Color(220, 220, 220);
		private static final Color ARC = new Color(90, 90, 90);

		private final Timer spinTimer;
		private int arcStart;

		private QrSpinnerPanel(int size) {
			setPreferredSize(new Dimension(size, size));
			setMinimumSize(new Dimension(size, size));
			setMaximumSize(new Dimension(size, size));
			setOpaque(true);
			setBackground(UIManager.getColor("Panel.background"));
			setBorder(BorderFactory.createLineBorder(
					SwingHelper.uiColor("Component.borderColor", new Color(200, 200, 200))));
			spinTimer = new Timer(30, ev -> {
				arcStart = (arcStart - 12 + 360) % 360;
				repaint();
			});
		}

		private void start() {
			if (!spinTimer.isRunning()) {
				spinTimer.start();
			}
		}

		private void stop() {
			spinTimer.stop();
		}

		@Override
		protected void paintComponent(Graphics g) {
			super.paintComponent(g);
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int diameter = Math.min(getWidth(), getHeight()) - 28;
				int x = (getWidth() - diameter) / 2;
				int y = (getHeight() - diameter) / 2;
				g2.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.setColor(TRACK);
				g2.drawOval(x, y, diameter, diameter);
				g2.setColor(ARC);
				g2.drawArc(x, y, diameter, diameter, arcStart, 270);
			} finally {
				g2.dispose();
			}
		}
	}

	public enum Result {
		SUCCESS,
		CANCELLED,
		FALLBACK_REQUESTED
	}

	@RequiredArgsConstructor
	@Getter
	public static final class Outcome {
		private final Result result;
		private final Session session;

		public static Outcome success(Session session) {
			return new Outcome(Result.SUCCESS, session);
		}

		public static Outcome cancelled() {
			return new Outcome(Result.CANCELLED, null);
		}

		public static Outcome fallback() {
			return new Outcome(Result.FALLBACK_REQUESTED, null);
		}
	}
}
