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
import com.skcraft.launcher.swing.LinkButton;
import com.skcraft.launcher.swing.SwingHelper;
import com.skcraft.launcher.swing.SwingIcons;
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
import java.awt.image.BufferedImage;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import static com.skcraft.launcher.util.SharedLocale.tr;

/**
 * Modal dialog for Microsoft sign-in.
 *
 * <p>
 * Offers browser-redirect sign-in as the primary action, plus a device-code
 * path (QR + code) for mobile or alternate devices. Polls until the device-code
 * flow completes, or returns a fallback request for redirect auth.
 * </p>
 */
public class MicrosoftLoginDialog extends JDialog {

	private static final int QR_SIZE = 144;
	private static final int QR_PADDING = 2;
	private static final int CODE_FIELD_FONT_SIZE = 32;
	private static final int COPY_ICON_SIZE = 18;
	private static final Icon COPY_ICON = SwingIcons.copy(COPY_ICON_SIZE);
	private static final Icon COPIED_ICON = SwingIcons.check(COPY_ICON_SIZE);

	private final Launcher launcher;
	private final MicrosoftLoginService.DeviceCodeDetails details;
	private final String authUrl;
	private final String verificationUri;

	private final JButton copyCodeButton = createCopyIconButton();
	private final JButton browserSignInButton = new JButton(tr("login.microsoft.device.useBrowser"));
	private final JButton cancelButton = new JButton(tr("button.cancel"));
	private final JTextField codeField;
	private final JLabel statusLabel = new JLabel(tr("login.microsoft.device.awaiting"));
	private final JLabel countdownLabel = new JLabel(" ");
	private final JLabel qrLabel = new JLabel();
	private final QrSpinnerPanel qrSpinnerPanel = new QrSpinnerPanel(QR_SIZE);
	private final JPanel qrContentPanel = new JPanel(new CardLayout());
	private final JPanel qrColumn = new JPanel(new MigLayout("insets 0", "[" + QR_SIZE + "!]", "[" + QR_SIZE + "!]"));
	private final JProgressBar pollIndicator = new JProgressBar();

	private final Timer countdownTimer;
	private Timer copyResetTimer;
	private ListenableFuture<Session> pollFuture;

	@Getter
	private Outcome outcome = Outcome.cancelled();
	private boolean codeExpired;

	private MicrosoftLoginDialog(Window owner, Launcher launcher, MicrosoftLoginService.DeviceCodeDetails details) {
		super(owner, tr("login.microsoft.device.dialogTitle"), ModalityType.DOCUMENT_MODAL);
		this.launcher = launcher;
		this.details = details;
		this.authUrl = details.getVerificationUriComplete() != null
				? details.getVerificationUriComplete()
				: details.getVerificationUri();
		this.verificationUri = details.getVerificationUri() != null
				? details.getVerificationUri()
				: authUrl;
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
		content.add(subtitleLabel, "gapbottom 16");

		browserSignInButton.setFont(browserSignInButton.getFont().deriveFont(Font.BOLD, browserSignInButton.getFont().getSize2D() + 1f));
		browserSignInButton.setMargin(new Insets(12, 18, 12, 18));
		browserSignInButton.putClientProperty("FlatLaf.styleClass", "primary");
		content.add(browserSignInButton, "growx, hmin 44, gapbottom 16");

		content.add(buildOrDivider(tr("login.microsoft.device.deviceHint")), "growx, gapbottom 16");
		content.add(buildDeviceCodePanel(), "alignx center, gapbottom 14");

		pollIndicator.setIndeterminate(true);
		pollIndicator.setPreferredSize(new Dimension(18, 18));
		statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD));
		countdownLabel.setForeground(SwingHelper.uiColor("Label.disabledForeground", Color.DARK_GRAY));

		JPanel statusRow = new JPanel(new MigLayout("insets 0, gap 10", "[][grow, fill][]"));
		statusRow.add(pollIndicator, "w 18!, h 18!");
		statusRow.add(statusLabel, "growx");
		statusRow.add(countdownLabel, "");
		content.add(statusRow, "growx");

		JPanel buttonBar = new JPanel(new MigLayout("insets 12 22 16 22, fillx", "[push][]"));
		buttonBar.add(cancelButton, "tag cancel");

		setLayout(new BorderLayout());
		add(content, BorderLayout.CENTER);
		add(buttonBar, BorderLayout.SOUTH);

		SwingHelper.styleDialogButton(copyCodeButton);
		SwingHelper.styleDialogButton(browserSignInButton);
		SwingHelper.styleDialogButton(cancelButton);

		copyCodeButton.addActionListener(ev -> handleCopy());
		browserSignInButton.addActionListener(ev -> handleBrowserSignIn());
		cancelButton.addActionListener(ev -> handleCancel());

		getRootPane().setDefaultButton(browserSignInButton);
		getRootPane().registerKeyboardAction(ev -> handleCancel(),
				KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
				JComponent.WHEN_IN_FOCUSED_WINDOW);
	}

	private JPanel buildOrDivider(String text) {
		Color mutedColor = SwingHelper.uiColor("Label.disabledForeground", Color.DARK_GRAY);
		JLabel label = new JLabel(text);
		label.setForeground(mutedColor);

		JPanel panel = new JPanel(new MigLayout("insets 0, fillx, gap 10", "[grow][][grow]"));
		panel.add(new JSeparator(), "growx");
		panel.add(label, "");
		panel.add(new JSeparator(), "growx");
		return panel;
	}

	private JPanel buildDeviceCodePanel() {
		Color mutedColor = SwingHelper.uiColor("Label.disabledForeground", Color.DARK_GRAY);

		codeField.setEditable(false);
		codeField.setHorizontalAlignment(SwingConstants.CENTER);
		codeField.setFont(new Font(Font.MONOSPACED, Font.BOLD, CODE_FIELD_FONT_SIZE));
		codeField.setColumns(Math.max(8, details.getUserCode().length() + 1));
		codeField.setBorder(new EmptyBorder(10, 16, 10, 8));
		Color codeBackground = SwingHelper.uiColor("TextField.background", Color.WHITE);
		codeField.setBackground(codeBackground);
		JPanel codeBox = new JPanel(new BorderLayout(0, 0));
		codeBox.setBackground(codeBackground);
		codeBox.setBorder(SwingHelper.uiLineBorder());
		copyCodeButton.setBackground(codeBackground);
		codeBox.add(codeField, BorderLayout.CENTER);
		codeBox.add(copyCodeButton, BorderLayout.EAST);

		String[] hintParts = tr("login.microsoft.device.browserHint").split("\\{0\\}", 2);

		JLabel prefix = new JLabel(hintParts[0]);
		prefix.setForeground(mutedColor);

		String linkText = verificationUri != null ? verificationUri : "";
		LinkButton link = new LinkButton(linkText);
		link.setFont(prefix.getFont());
		if (verificationUri != null && !verificationUri.isEmpty()) {
			link.addActionListener(ev -> SwingHelper.openURL(verificationUri, this));
		}

		JPanel openRow = new JPanel(new MigLayout("insets 0, gapx 0"));
		openRow.add(prefix);
		openRow.add(link);

		JTextArea suffix = new JTextArea(hintParts.length > 1 ? hintParts[1].trim() : "");
		suffix.setEditable(false);
		suffix.setFocusable(false);
		suffix.setOpaque(false);
		suffix.setBorder(null);
		suffix.setLineWrap(true);
		suffix.setWrapStyleWord(true);
		suffix.setFont(prefix.getFont());
		suffix.setForeground(mutedColor);

		int rightWidth = Math.max(openRow.getPreferredSize().width, codeBox.getPreferredSize().width);
		JPanel right = new JPanel(new MigLayout("insets 0, wrap 1, gapy 2", "[" + rightWidth + "!]"));
		right.add(openRow, "growx");
		right.add(suffix, "growx");
		right.add(codeBox, "gaptop 8, alignx left");

		qrLabel.setHorizontalAlignment(SwingConstants.CENTER);
		qrLabel.setVerticalAlignment(SwingConstants.CENTER);
		qrLabel.setOpaque(true);
		qrLabel.setBackground(Color.WHITE);
		qrLabel.setBorder(BorderFactory.createEmptyBorder(QR_PADDING, QR_PADDING, QR_PADDING, QR_PADDING));

		qrContentPanel.add(qrSpinnerPanel, "spinner");
		qrContentPanel.add(qrLabel, "qr");

		qrColumn.add(qrContentPanel, "w " + QR_SIZE + "!, h " + QR_SIZE + "!");
		qrColumn.setVisible(authUrl != null && !authUrl.isEmpty());

		JPanel panel = new JPanel(new MigLayout("insets 0, hidemode 3", "[]16[]", "[center]"));
		panel.add(qrColumn, "center");
		panel.add(right, "center");
		return panel;
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
		countdownLabel.setText(" ");
		statusLabel.setText(tr("login.microsoft.device.expiredStatus"));
		pollIndicator.setIndeterminate(false);
		pollIndicator.setVisible(false);
		if (pollFuture != null && !pollFuture.isDone()) {
			pollFuture.cancel(true);
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

	private void handleBrowserSignIn() {
		outcome = Outcome.browser();
		closeDialog();
	}

	private void handleCancel() {
		outcome = Outcome.cancelled();
		closeDialog();
	}

	private void hideQrOption() {
		qrSpinnerPanel.stop();
		qrColumn.setVisible(false);
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
		ProgressDialog.showProgress(owner, fetchFuture, progress, tr("login.microsoft.device.fetchingTitle"),
				tr("login.microsoft.device.starting"));

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
			setBorder(SwingHelper.uiLineBorder());
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
		BROWSER_REQUESTED
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

		public static Outcome browser() {
			return new Outcome(Result.BROWSER_REQUESTED, null);
		}
	}
}
