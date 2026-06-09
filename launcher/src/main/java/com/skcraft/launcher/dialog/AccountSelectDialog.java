package com.skcraft.launcher.dialog;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.skcraft.concurrency.ObservableFuture;
import com.skcraft.concurrency.ProgressObservable;
import com.skcraft.concurrency.SettableProgress;
import com.skcraft.launcher.Launcher;
import com.skcraft.launcher.auth.*;
import com.skcraft.launcher.persistence.Persistence;
import com.skcraft.launcher.swing.LinedBoxPanel;
import com.skcraft.launcher.swing.SwingHelper;
import com.skcraft.launcher.util.SharedLocale;
import com.skcraft.launcher.util.SwingExecutor;
import lombok.RequiredArgsConstructor;

import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.Callable;

public class AccountSelectDialog extends JDialog {
	private final JList<SavedSession> accountList;
	private final JButton loginButton = new JButton(SharedLocale.tr("accounts.play"));
	private final JButton cancelButton = new JButton(SharedLocale.tr("button.cancel"));
	private final JButton addAccountButton = createAddAccountButton();
	private final JButton removeSelected = createForgetAccountButton();
	private final JButton offlineButton = new JButton(SharedLocale.tr("login.playOffline"));
	private final LinedBoxPanel buttonsPanel = new LinedBoxPanel(true);

	private final Launcher launcher;
	private Session selected;

	public AccountSelectDialog(Window owner, Launcher launcher) {
		super(owner, ModalityType.DOCUMENT_MODAL);

		this.launcher = launcher;
		this.accountList = new JList<>(launcher.getAccounts());

		setTitle(SharedLocale.tr("accounts.title"));
		initComponents();
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		setMinimumSize(new Dimension(480, 280));
		setResizable(false);
		pack();
		setLocationRelativeTo(owner);
	}

	private void initComponents() {
		setLayout(new BorderLayout());

		accountList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		accountList.setLayoutOrientation(JList.VERTICAL);
		accountList.setVisibleRowCount(0);
		accountList.setCellRenderer(new AccountRenderer());
		accountList.setFixedCellHeight(42);
		accountList.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		accountList.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent event) {
				if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
					attemptExistingLogin(accountList.getSelectedValue());
				}
			}
		});
		accountList.addListSelectionListener(ev -> updateActionState());

		JScrollPane accountPane = new JScrollPane(accountList);
		accountPane.setPreferredSize(new Dimension(280, 150));
		accountPane.setAlignmentX(Component.LEFT_ALIGNMENT);
		accountPane.setBorder(BorderFactory.createLineBorder(SwingHelper.uiColor("Separator.foreground", Color.GRAY)));

		loginButton.setFont(loginButton.getFont().deriveFont(Font.BOLD));
		SwingHelper.styleDialogButton(cancelButton);
		SwingHelper.styleDialogButton(loginButton);
		SwingHelper.styleDialogButton(offlineButton);
		if (launcher.getConfig().isOfflineEnabled()) {
			SwingHelper.alignButtonSizes(offlineButton, cancelButton, loginButton);
		} else {
			SwingHelper.alignButtonSizes(cancelButton, loginButton);
		}

		// Start Buttons
		buttonsPanel.setBorder(BorderFactory.createEmptyBorder(26, 13, 13, 13));
		if (launcher.getConfig().isOfflineEnabled()) {
			buttonsPanel.addElement(offlineButton);
		}
		buttonsPanel.addGlue();
		buttonsPanel.addElement(cancelButton);
		buttonsPanel.addElement(loginButton);

		JPanel actionsPanel = new JPanel(new MigLayout("insets 10, wrap 1, fillx", "[grow, fill]"));
		actionsPanel.setBorder(BorderFactory.createCompoundBorder(
				new LineBorder(SwingHelper.uiColor("Separator.foreground", Color.GRAY)),
				new EmptyBorder(4, 4, 4, 4)));

		JLabel actionsLabel = new JLabel(SharedLocale.tr("accounts.actionsTitle"));
		actionsLabel.setFont(actionsLabel.getFont().deriveFont(Font.BOLD));

		Insets actionInsets = new Insets(10, 12, 10, 12);
		addAccountButton.setMargin(actionInsets);
		removeSelected.setMargin(actionInsets);
		SwingHelper.styleDialogButton(addAccountButton);
		SwingHelper.styleDialogButton(removeSelected);
		SwingHelper.alignButtonSizes(addAccountButton, removeSelected);
		Dimension actionSize = addAccountButton.getPreferredSize();
		actionSize.height = Math.max(actionSize.height, 38);
		addAccountButton.setPreferredSize(actionSize);
		addAccountButton.setMinimumSize(actionSize);
		removeSelected.setPreferredSize(actionSize);
		removeSelected.setMinimumSize(actionSize);

		actionsPanel.add(actionsLabel, "gapbottom 8");
		actionsPanel.add(addAccountButton, "growx");
		actionsPanel.add(removeSelected, "growx, gaptop 6");

		JPanel contentPanel = new JPanel(new BorderLayout(12, 0));
		contentPanel.add(accountPane, BorderLayout.CENTER);
		contentPanel.add(actionsPanel, BorderLayout.EAST);

		JPanel bodyPanel = new JPanel(new BorderLayout(0, 10));
		bodyPanel.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));
		bodyPanel.add(createHeaderPanel(), BorderLayout.NORTH);
		bodyPanel.add(contentPanel, BorderLayout.CENTER);

		add(bodyPanel, BorderLayout.CENTER);
		add(buttonsPanel, BorderLayout.SOUTH);

		loginButton.addActionListener(ev -> attemptExistingLogin(accountList.getSelectedValue()));
		cancelButton.addActionListener(ev -> dispose());

		addAccountButton.addActionListener(ev -> beginMicrosoftLogin());

		offlineButton.addActionListener(
				ev -> setResult(new OfflineSession(launcher.getProperties().getProperty("offlinePlayerName"))));

		removeSelected.addActionListener(ev -> {
			if (accountList.getSelectedValue() != null) {
				boolean confirmed = SwingHelper.confirmDialog(this, SharedLocale.tr("accounts.confirmForget"),
						SharedLocale.tr("accounts.confirmForgetTitle"));

				if (confirmed) {
					launcher.getAccounts().remove(accountList.getSelectedValue());
				}
			}
		});

		if (accountList.getModel().getSize() > 0) {
			accountList.setSelectedIndex(0);
		}
		getRootPane().setDefaultButton(loginButton);
		updateActionState();
	}

	private JPanel createHeaderPanel() {
		JPanel headerPanel = new JPanel(new BorderLayout(0, 4));
		headerPanel.setBackground(UIManager.getColor("Panel.background"));
		headerPanel.setOpaque(true);
		headerPanel.setBorder(BorderFactory.createCompoundBorder(
				new LineBorder(SwingHelper.uiColor("Separator.foreground", Color.GRAY)),
				new EmptyBorder(12, 12, 12, 12)));

		JLabel titleLabel = new JLabel(SharedLocale.tr("accounts.title"));
		titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, titleLabel.getFont().getSize2D() + 1f));

		JLabel subtitleLabel = new JLabel(SharedLocale.tr("accounts.subtitle"));
		subtitleLabel.setForeground(SwingHelper.uiColor("Label.disabledForeground", Color.DARK_GRAY));

		headerPanel.add(titleLabel, BorderLayout.NORTH);
		headerPanel.add(subtitleLabel, BorderLayout.CENTER);
		return headerPanel;
	}

	private void updateActionState() {
		boolean hasSelection = accountList.getSelectedValue() != null;
		loginButton.setEnabled(hasSelection);
		SwingHelper.updateDialogButtonCursor(loginButton);
		removeSelected.setEnabled(hasSelection);
		removeSelected.setIcon(createForgetIcon(14, hasSelection));
		updateForgetButtonStyle(removeSelected, hasSelection);
	}

	private static JButton createAddAccountButton() {
		JButton button = new JButton(SharedLocale.tr("accounts.addAccount"), createPlusIcon(14));
		button.setToolTipText(SharedLocale.tr("accounts.addAccountTooltip"));
		button.setIconTextGap(8);
		button.setFont(button.getFont().deriveFont(Font.BOLD));
		return button;
	}

	private static JButton createForgetAccountButton() {
		JButton button = new JButton(SharedLocale.tr("accounts.removeSelected"), createForgetIcon(14, false));
		button.setToolTipText(SharedLocale.tr("accounts.forgetTooltip"));
		button.setIconTextGap(8);
		return button;
	}

	private static void updateForgetButtonStyle(JButton button, boolean enabled) {
		SwingHelper.updateDialogButtonCursor(button);
		if (enabled) {
			button.setForeground(new Color(140, 45, 45));
		} else {
			button.setForeground(UIManager.getColor("Label.disabledForeground"));
		}
	}

	private static Icon createPlusIcon(int size) {
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(new Color(55, 55, 55));
			g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			int c = size / 2;
			int half = size / 4;
			g.drawLine(c - half, c, c + half, c);
			g.drawLine(c, c - half, c, c + half);
		} finally {
			g.dispose();
		}
		return new ImageIcon(image);
	}

	private static Icon createForgetIcon(int size, boolean enabled) {
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			Color color = enabled ? new Color(140, 45, 45) : SwingHelper.uiColor("Label.disabledForeground", Color.GRAY);
			g.setColor(color);
			g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			int pad = size / 5;
			g.drawLine(pad, pad, size - pad, size - pad);
			g.drawLine(size - pad, pad, pad, size - pad);
		} finally {
			g.dispose();
		}
		return new ImageIcon(image);
	}

	@Override
	public void dispose() {
		accountList.setModel(new DefaultListModel<>());
		super.dispose();
	}

	public static Session showAccountRequest(Window owner, Launcher launcher) {
		AccountSelectDialog dialog = new AccountSelectDialog(owner, launcher);
		dialog.setVisible(true);

		if (dialog.selected != null && dialog.selected.isOnline()) {
			launcher.getAccounts().update(dialog.selected.toSavedSession());
		}

		Persistence.commitAndForget(launcher.getAccounts());

		return dialog.selected;
	}

	private void setResult(Session result) {
		this.selected = result;
		dispose();
	}

	private void beginMicrosoftLogin() {
		MicrosoftLoginDialog.Outcome outcome = MicrosoftLoginDialog.showLogin(this, launcher);
		handleMicrosoftOutcome(outcome);
	}

	private void handleMicrosoftOutcome(MicrosoftLoginDialog.Outcome outcome) {
		switch (outcome.getResult()) {
			case SUCCESS:
				Session newSession = outcome.getSession();
				if (newSession != null) {
					launcher.getAccounts().update(newSession.toSavedSession());
					setResult(newSession);
				}
				return;
			case FALLBACK_REQUESTED:
				attemptMicrosoftBrowserLogin();
				return;
			case CANCELLED:
			default:
				break;
		}
	}

	private void attemptMicrosoftBrowserLogin() {
		String status = SharedLocale.tr("login.microsoft.seeBrowser");
		SettableProgress progress = new SettableProgress(status, -1);

		ListenableFuture<?> future = launcher.getExecutor().submit(() -> {
			Session newSession = launcher.getMicrosoftLogin()
					.login(() -> progress.set(SharedLocale.tr("login.loggingInStatus"), -1));

			if (newSession != null) {
				launcher.getAccounts().update(newSession.toSavedSession());
				setResult(newSession);
			}

			return null;
		});

		ProgressDialog.showProgress(this, future, progress,
				SharedLocale.tr("login.loggingInTitle"), status);
		SwingHelper.addErrorDialogCallback(this, future);
	}

	private void attemptExistingLogin(SavedSession session) {
		if (session == null)
			return;

		LoginService loginService = launcher.getLoginService(session.getType());
		RestoreSessionCallable callable = new RestoreSessionCallable(loginService, session);

		ObservableFuture<Session> future = new ObservableFuture<>(launcher.getExecutor().submit(callable), callable);
		Futures.addCallback(future, new FutureCallback<Session>() {
			@Override
			public void onSuccess(Session result) {
				setResult(result);
			}

			@Override
			public void onFailure(Throwable t) {
				if (t instanceof AuthenticationException && ((AuthenticationException) t).isInvalidatedSession()) {
					// Just need to log in again
					relogin(session, t.getLocalizedMessage());
				} else {
					SwingHelper.showErrorDialog(AccountSelectDialog.this, t.getLocalizedMessage(),
							SharedLocale.tr("errorTitle"), t);
				}
			}
		}, SwingExecutor.INSTANCE);

		ProgressDialog.showProgress(this, future, SharedLocale.tr("login.loggingInTitle"),
				SharedLocale.tr("login.loggingInStatus"));
	}

	/**
	 * Re-login to an expired session
	 */
	private void relogin(SavedSession session, String message) {
		if (session.getType() == UserType.MICROSOFT) {
			beginMicrosoftLogin();
		} else {
			LoginDialog.ReloginDetails details = new LoginDialog.ReloginDetails(session.getUsername(),
					SharedLocale.tr("login.relogin", message));
			Session newSession = LoginDialog.showLoginRequest(AccountSelectDialog.this, launcher, details);

			launcher.getAccounts().update(newSession.toSavedSession());
			setResult(newSession);
		}
	}

	@RequiredArgsConstructor
	private static class RestoreSessionCallable implements Callable<Session>, ProgressObservable {
		private final LoginService service;
		private final SavedSession session;

		@Override
		public Session call() throws Exception {
			return service.restore(session);
		}

		@Override
		public String getStatus() {
			return SharedLocale.tr("accounts.refreshingStatus");
		}

		@Override
		public double getProgress() {
			return -1;
		}
	}

	private static class AccountRenderer extends JLabel implements ListCellRenderer<SavedSession> {
		public AccountRenderer() {
			setHorizontalAlignment(LEFT);
			setIconTextGap(10);
			setBorder(new EmptyBorder(4, 6, 4, 6));
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends SavedSession> list, SavedSession value, int index,
				boolean isSelected, boolean cellHasFocus) {
			setText(value.getUsername());
			if (value.getAvatarImage() != null) {
				setIcon(new ImageIcon(value.getAvatarImage()));
			} else {
				setIcon(SwingHelper.createIcon(Launcher.class, "default_skin.png", 32, 32));
			}

			if (isSelected) {
				setOpaque(true);
				setBackground(list.getSelectionBackground());
				setForeground(list.getSelectionForeground());
			} else {
				setOpaque(false);
				setForeground(list.getForeground());
			}

			return this;
		}
	}
}
