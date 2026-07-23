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
import com.skcraft.launcher.swing.InstanceRowStyle;
import com.skcraft.launcher.swing.LinedBoxPanel;
import com.skcraft.launcher.swing.SwingHelper;
import com.skcraft.launcher.util.SharedLocale;
import com.skcraft.launcher.util.SwingExecutor;
import lombok.RequiredArgsConstructor;

import net.miginfocom.swing.MigLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

public class AccountSelectDialog extends JDialog {
	private static final int ACTION_ICON_SIZE = 14;
	private static final int TITLE_SUBTITLE_GAP = 0;
	private static final String OFFLINE_USERNAME_PATTERN = "[A-Za-z0-9_]{3,16}";

	private final JList<SavedSession> accountList;
	private final JButton loginButton;
	private final JButton cancelButton = new JButton(SharedLocale.tr("button.cancel"));
	private final JButton addAccountButton = createAddAccountButton();
	private final JButton removeSelected = createForgetAccountButton();
	private final JButton offlineButton = createOfflineAccountButton();
	private final LinedBoxPanel buttonsPanel = new LinedBoxPanel(true);

	private final Launcher launcher;
	private final boolean manageOnly;

	public AccountSelectDialog(Window owner, Launcher launcher) {
		this(owner, launcher, false);
	}

	public AccountSelectDialog(Window owner, Launcher launcher, boolean manageOnly) {
		super(owner, ModalityType.DOCUMENT_MODAL);

		this.launcher = launcher;
		this.manageOnly = manageOnly;
		this.accountList = new JList<>(launcher.getAccounts());
		this.loginButton = new JButton(SharedLocale.tr(manageOnly ? "accounts.useAccount" : "accounts.play"));
		boolean offlineEnabled = launcher.getConfig().isOfflineEnabled();
		offlineButton.setVisible(offlineEnabled);
		offlineButton.setEnabled(offlineEnabled);

		setTitle(SharedLocale.tr(manageOnly ? "accounts.manageTitle" : "accounts.title"));
		initComponents();
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		setMinimumSize(new Dimension(480, 280));
		setResizable(false);
		pack();
		setLocationRelativeTo(owner);
	}

	private void initComponents() {
		setLayout(new BorderLayout());
		Border panelBorder = BorderFactory.createLineBorder(SwingHelper.uiColor("Component.borderColor", Color.GRAY));

		accountList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		accountList.setLayoutOrientation(JList.VERTICAL);
		accountList.setVisibleRowCount(0);
		accountList.setCellRenderer(new AccountRenderer());
		accountList.setFixedCellHeight(46);
		accountList.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		accountList.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent event) {
				if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
					confirmSelection(accountList.getSelectedValue());
				}
			}
		});
		accountList.addListSelectionListener(ev -> updateActionState());

		JScrollPane accountPane = new JScrollPane(accountList);
		accountPane.setPreferredSize(new Dimension(280, 150));
		accountPane.setAlignmentX(Component.LEFT_ALIGNMENT);
		accountPane.setBorder(panelBorder);

		loginButton.setFont(loginButton.getFont().deriveFont(Font.BOLD));
		SwingHelper.styleDialogButton(cancelButton);
		SwingHelper.styleDialogButton(loginButton);
		SwingHelper.styleDialogButton(offlineButton);
		SwingHelper.updateDialogButtonCursor(offlineButton);
		SwingHelper.alignButtonSizes(cancelButton, loginButton);

		buttonsPanel.setBorder(BorderFactory.createEmptyBorder(26, 13, 13, 13));
		buttonsPanel.addElement(offlineButton);
		buttonsPanel.addGlue();
		buttonsPanel.addElement(cancelButton);
		buttonsPanel.addElement(loginButton);

		JPanel actionsPanel = new JPanel(new MigLayout("insets 10, wrap 1, fillx", "[grow, fill]"));
		actionsPanel.setBorder(BorderFactory.createCompoundBorder(
				panelBorder,
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

		loginButton.addActionListener(ev -> confirmSelection(accountList.getSelectedValue()));
		cancelButton.addActionListener(ev -> dispose());

		addAccountButton.addActionListener(ev -> beginMicrosoftLogin());

		offlineButton.addActionListener(ev -> beginOfflineAccount());

		removeSelected.addActionListener(ev -> {
			if (accountList.getSelectedValue() != null) {
				boolean confirmed = SwingHelper.confirmDialog(this, SharedLocale.tr("accounts.confirmForget"),
						SharedLocale.tr("accounts.confirmForgetTitle"));

				if (confirmed) {
					launcher.getAccounts().remove(accountList.getSelectedValue());
					Persistence.commitAndForget(launcher.getAccounts());
					selectActiveAccount();
				}
			}
		});

		selectActiveAccount();
		getRootPane().setDefaultButton(loginButton);
		updateActionState();
	}

	private void selectActiveAccount() {
		SavedSession active = launcher.getAccounts().getActiveAccount();
		if (active != null) {
			accountList.setSelectedValue(active, true);
		} else if (accountList.getModel().getSize() > 0) {
			accountList.setSelectedIndex(0);
		} else {
			accountList.clearSelection();
		}
		updateActionState();
	}

	private JPanel createHeaderPanel() {
		JPanel headerPanel = new JPanel(new GridBagLayout());
		headerPanel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(SwingHelper.uiColor("Component.borderColor", Color.GRAY)),
				new EmptyBorder(12, 12, 12, 12)));

		JLabel titleLabel = new JLabel(SharedLocale.tr(manageOnly ? "accounts.manageTitle" : "accounts.title"));
		titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, titleLabel.getFont().getSize2D() + 1f));

		JLabel subtitleLabel = new JLabel(SharedLocale.tr(manageOnly ? "accounts.manageSubtitle" : "accounts.subtitle"));
		subtitleLabel.setForeground(SwingHelper.uiColor("Label.disabledForeground", Color.DARK_GRAY));

		GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridx = 0;
		constraints.gridy = 0;
		constraints.weightx = 1.0;
		constraints.anchor = GridBagConstraints.WEST;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		headerPanel.add(titleLabel, constraints);
		constraints = (GridBagConstraints) constraints.clone();
		constraints.gridy = 1;
		constraints.insets = new Insets(TITLE_SUBTITLE_GAP, 0, 0, 0);
		headerPanel.add(subtitleLabel, constraints);
		return headerPanel;
	}

	private void updateActionState() {
		boolean hasSelection = accountList.getSelectedValue() != null;
		loginButton.setEnabled(hasSelection);
		SwingHelper.updateDialogButtonCursor(loginButton);
		removeSelected.setEnabled(hasSelection);
		removeSelected.setIcon(createForgetIcon(ACTION_ICON_SIZE, hasSelection));
		updateForgetButtonStyle(removeSelected, hasSelection);
	}

	private static JButton createAddAccountButton() {
		JButton button = new JButton(SharedLocale.tr("accounts.addAccount"), createPlusIcon(ACTION_ICON_SIZE));
		button.setToolTipText(SharedLocale.tr("accounts.addAccountTooltip"));
		button.setIconTextGap(8);
		button.setFont(button.getFont().deriveFont(Font.BOLD));
		return button;
	}

	private static JButton createOfflineAccountButton() {
		JButton button = new JButton(SharedLocale.tr("accounts.offlineAccount"));
		button.setToolTipText(SharedLocale.tr("accounts.offlineAccountTooltip"));
		return button;
	}

	private static JButton createForgetAccountButton() {
		JButton button = new JButton(SharedLocale.tr("accounts.removeSelected"), createForgetIcon(ACTION_ICON_SIZE, false));
		button.setToolTipText(SharedLocale.tr("accounts.forgetTooltip"));
		button.setIconTextGap(8);
		return button;
	}

	private static void updateForgetButtonStyle(JButton button, boolean enabled) {
		SwingHelper.updateDialogButtonCursor(button);
		Color foreground = getForgetButtonForeground(enabled);
		button.setForeground(foreground);
		button.setIcon(createForgetIcon(ACTION_ICON_SIZE, foreground));
		button.setDisabledIcon(createForgetIcon(ACTION_ICON_SIZE, getForgetButtonForeground(false)));
	}

	private static Icon createPlusIcon(int size) {
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(SwingHelper.uiColor("Button.foreground", new Color(55, 55, 55)));
			g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			int c = size / 2;
			int pad = size / 5;
			g.drawLine(pad, c, size - pad, c);
			g.drawLine(c, pad, c, size - pad);
		} finally {
			g.dispose();
		}
		return new ImageIcon(image);
	}

	private static Icon createForgetIcon(int size, boolean enabled) {
		return createForgetIcon(size, getForgetButtonForeground(enabled));
	}

	private static Icon createForgetIcon(int size, Color color) {
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
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

	private static Color getForgetButtonForeground(boolean enabled) {
		if (!enabled) {
			return SwingHelper.uiColor("Button.disabledText",
					SwingHelper.uiColor("Label.disabledForeground", Color.GRAY));
		}

		Color color = UIManager.getColor("Actions.Red");
		if (color == null) {
			color = UIManager.getColor("Component.error.focusedBorderColor");
		}
		if (color == null) {
			color = UIManager.getColor("Component.error.borderColor");
		}
		if (color == null) {
			color = new Color(160, 40, 40);
		}

		Color background = SwingHelper.uiColor("Button.background",
				SwingHelper.uiColor("Panel.background", Color.WHITE));
		if (contrastRatio(color, background) >= 4.5d) {
			return color;
		}

		return relativeLuminance(background) < 0.5d
				? new Color(255, 130, 130)
				: new Color(145, 35, 35);
	}

	private static double contrastRatio(Color a, Color b) {
		double lighter = Math.max(relativeLuminance(a), relativeLuminance(b));
		double darker = Math.min(relativeLuminance(a), relativeLuminance(b));
		return (lighter + 0.05d) / (darker + 0.05d);
	}

	private static double relativeLuminance(Color color) {
		return 0.2126d * linearChannel(color.getRed())
				+ 0.7152d * linearChannel(color.getGreen())
				+ 0.0722d * linearChannel(color.getBlue());
	}

	private static double linearChannel(int value) {
		double channel = value / 255.0d;
		return channel <= 0.03928d
				? channel / 12.92d
				: Math.pow((channel + 0.055d) / 1.055d, 2.4d);
	}

	@Override
	public void dispose() {
		accountList.setModel(new DefaultListModel<>());
		super.dispose();
	}

	/**
	 * Resolve a session for launching: restore the active account, or Microsoft sign-in if none.
	 */
	public static Session showAccountRequest(Window owner, Launcher launcher) {
		SavedSession active = launcher.getAccounts().getActiveAccount();
		if (active != null) {
			return restoreAccount(owner, launcher, active);
		}

		Session session = requestMicrosoftLogin(owner, launcher);
		if (session != null && session.isOnline()) {
			launcher.getAccounts().update(session.toSavedSession());
			Persistence.commitAndForget(launcher.getAccounts());
		}

		return session;
	}

	/**
	 * Open the account switcher without launching.
	 * The management dialog remains available when there are no saved accounts.
	 */
	public static void showManageAccounts(Window owner, Launcher launcher) {
		AccountSelectDialog dialog = new AccountSelectDialog(owner, launcher, true);
		dialog.setVisible(true);
		Persistence.commitAndForget(launcher.getAccounts());
	}

	private void confirmSelection(SavedSession session) {
		if (session == null) {
			return;
		}

		if (manageOnly) {
			launcher.getAccounts().setActiveAccount(session);
			Persistence.commitAndForget(launcher.getAccounts());
			dispose();
			return;
		}

		attemptExistingLogin(session);
	}

	private void setResult(Session result) {
		dispose();
	}

	private void beginMicrosoftLogin() {
		Session newSession = requestMicrosoftLogin(this, launcher);
		if (newSession != null) {
			launcher.getAccounts().update(newSession.toSavedSession());
			Persistence.commitAndForget(launcher.getAccounts());
			if (manageOnly) {
				selectActiveAccount();
			} else {
				setResult(newSession);
			}
		}
	}

	private void beginOfflineAccount() {
		SavedSession offlineAccount = createOfflineAccount(this, launcher);
		if (offlineAccount == null) {
			return;
		}

		if (manageOnly) {
			selectActiveAccount();
		} else {
			setResult(sanitizeOfflineAccount(launcher, offlineAccount));
		}
	}

	private static SavedSession createOfflineAccount(Window owner, Launcher launcher) {
		String username = promptOfflineUsername(owner, launcher.getProperties().getProperty("offlinePlayerName"));
		if (username == null) {
			return null;
		}

		SavedSession offlineAccount = launcher.getAccounts().getOfflineAccount(username);

		if (offlineAccount == null) {
			offlineAccount = new OfflineSession(username).toSavedSession();
		}

		return persistOfflineAccount(launcher, offlineAccount);
	}

	/**
	 * Force offline UUID from username and strip avatar/tokens so Mojang skins
	 * cannot ride along into the game process.
	 */
	private static OfflineSession sanitizeOfflineAccount(Launcher launcher, SavedSession session) {
		OfflineSession offlineSession = OfflineSession.fromSavedSession(session);
		persistOfflineAccount(launcher, offlineSession.toSavedSession());
		return offlineSession;
	}

	private static SavedSession persistOfflineAccount(Launcher launcher, SavedSession offlineAccount) {
		SavedSession cleaned = new OfflineSession(offlineAccount.getUsername()).toSavedSession();
		launcher.getAccounts().putOfflineAccount(cleaned);
		Persistence.commitAndForget(launcher.getAccounts());
		return cleaned;
	}

	private static String promptOfflineUsername(Window owner, String defaultUsername) {
		String initialValue = defaultUsername != null ? defaultUsername : "";

		while (true) {
			String username = (String) JOptionPane.showInputDialog(owner,
					SharedLocale.tr("accounts.offlineAccountNamePrompt"),
					SharedLocale.tr("accounts.offlineAccountTitle"),
					JOptionPane.QUESTION_MESSAGE, null, null, initialValue);

			if (username == null) {
				return null;
			}

			username = username.trim();
			if (isValidOfflineUsername(username)) {
				return username;
			}

			initialValue = username;
			SwingHelper.showMessageDialog(owner,
					SharedLocale.tr("accounts.offlineAccountNameInvalid"),
					SharedLocale.tr("accounts.offlineAccountTitle"), null, JOptionPane.WARNING_MESSAGE);
		}
	}

	private static boolean isValidOfflineUsername(String username) {
		return username != null && username.matches(OFFLINE_USERNAME_PATTERN);
	}

	private static Session requestMicrosoftLogin(Window owner, Launcher launcher) {
		MicrosoftLoginDialog.Outcome outcome = MicrosoftLoginDialog.showLogin(owner, launcher);
		switch (outcome.getResult()) {
			case SUCCESS:
				return outcome.getSession();
			case FALLBACK_REQUESTED:
				return attemptMicrosoftBrowserLogin(owner, launcher);
			case CANCELLED:
			default:
				return null;
		}
	}

	private static Session attemptMicrosoftBrowserLogin(Window owner, Launcher launcher) {
		String status = SharedLocale.tr("login.microsoft.seeBrowser");
		SettableProgress progress = new SettableProgress(status, -1);

		ListenableFuture<Session> future = launcher.getExecutor().submit(() -> {
			return launcher.getMicrosoftLogin()
					.login(() -> progress.set(SharedLocale.tr("login.loggingInStatus"), -1));
		});

		ProgressDialog.showProgress(owner, future, progress,
				SharedLocale.tr("login.loggingInTitle"), status);
		SwingHelper.addErrorDialogCallback(owner, future);

		try {
			return future.get();
		} catch (CancellationException e) {
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (ExecutionException e) {
			return null;
		}
	}

	private static Session restoreAccount(Window owner, Launcher launcher, SavedSession session) {
		if (AccountList.isOfflineAccount(session)) {
			return sanitizeOfflineAccount(launcher, session);
		}

		LoginService loginService = launcher.getLoginService(session.getType());
		RestoreSessionCallable callable = new RestoreSessionCallable(loginService, session);

		ObservableFuture<Session> future = new ObservableFuture<>(launcher.getExecutor().submit(callable), callable);

		ProgressDialog.showProgress(owner, future, SharedLocale.tr("login.loggingInTitle"),
				SharedLocale.tr("login.loggingInStatus"));

		try {
			Session result = future.get();
			if (result != null && result.isOnline()) {
				launcher.getAccounts().update(result.toSavedSession());
				Persistence.commitAndForget(launcher.getAccounts());
			}
			return result;
		} catch (CancellationException e) {
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			if (cause instanceof AuthenticationException
					&& ((AuthenticationException) cause).isInvalidatedSession()) {
				return reloginExpired(owner, launcher, session, cause.getLocalizedMessage());
			}
			SwingHelper.showErrorDialog(owner, cause.getLocalizedMessage(), SharedLocale.tr("errorTitle"), cause);
			return null;
		}
	}

	private static Session reloginExpired(Window owner, Launcher launcher, SavedSession session, String message) {
		if (session.getType() == UserType.MICROSOFT) {
			Session newSession = requestMicrosoftLogin(owner, launcher);
			if (newSession != null && newSession.isOnline()) {
				launcher.getAccounts().update(newSession.toSavedSession());
				Persistence.commitAndForget(launcher.getAccounts());
			}
			return newSession;
		}

		LoginDialog.ReloginDetails details = new LoginDialog.ReloginDetails(session.getUsername(),
				SharedLocale.tr("login.relogin", message));
		Session newSession = LoginDialog.showLoginRequest(owner, launcher, details);
		if (newSession != null) {
			launcher.getAccounts().update(newSession.toSavedSession());
			Persistence.commitAndForget(launcher.getAccounts());
		}
		return newSession;
	}

	@SuppressWarnings("null")
	private void attemptExistingLogin(SavedSession session) {
		if (session == null) {
			return;
		}

		if (AccountList.isOfflineAccount(session)) {
			setResult(sanitizeOfflineAccount(launcher, session));
			return;
		}

		LoginService loginService = launcher.getLoginService(session.getType());
		RestoreSessionCallable callable = new RestoreSessionCallable(loginService, session);

		ObservableFuture<Session> future = new ObservableFuture<>(launcher.getExecutor().submit(callable), callable);
		@Nonnull Executor callbackExecutor = SwingExecutor.INSTANCE;
		Futures.addCallback(future, new FutureCallback<Session>() {
			@Override
			public void onSuccess(@Nullable Session result) {
				if (result != null && result.isOnline()) {
					launcher.getAccounts().update(result.toSavedSession());
					Persistence.commitAndForget(launcher.getAccounts());
				}
				setResult(result);
			}

			@Override
			public void onFailure(@Nonnull Throwable t) {
				if (t instanceof AuthenticationException && ((AuthenticationException) t).isInvalidatedSession()) {
					relogin(session, t.getLocalizedMessage());
				} else {
					SwingHelper.showErrorDialog(AccountSelectDialog.this, t.getLocalizedMessage(),
							SharedLocale.tr("errorTitle"), t);
				}
			}
		}, callbackExecutor);

		ProgressDialog.showProgress(this, future, SharedLocale.tr("login.loggingInTitle"),
				SharedLocale.tr("login.loggingInStatus"));
	}

	private void relogin(SavedSession session, String message) {
		if (session.getType() == UserType.MICROSOFT) {
			beginMicrosoftLogin();
		} else {
			LoginDialog.ReloginDetails details = new LoginDialog.ReloginDetails(session.getUsername(),
					SharedLocale.tr("login.relogin", message));
			Session newSession = LoginDialog.showLoginRequest(AccountSelectDialog.this, launcher, details);

			if (newSession != null) {
				launcher.getAccounts().update(newSession.toSavedSession());
				Persistence.commitAndForget(launcher.getAccounts());
				setResult(newSession);
			}
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

	private static class AccountRenderer extends JPanel implements ListCellRenderer<SavedSession> {
		private static final int AVATAR_SIZE = 32;
		private static final int ICON_TEXT_GAP = 10;
		private static final float SUBTITLE_FONT_SIZE = 11.0f;

		private final JPanel accountPanel = new JPanel(new BorderLayout(ICON_TEXT_GAP, 0));
		private final JLabel avatarLabel = new JLabel();
		private final JLabel usernameLabel = new JLabel();
		private final JLabel typeLabel = new JLabel();
		private final Icon defaultAvatar = SwingHelper.createIcon(Launcher.class, "default_skin.png", 32, 32);

		public AccountRenderer() {
			super(new BorderLayout());
			setOpaque(true);
			setBorder(new EmptyBorder(3, 2, 3, 2));

			accountPanel.setOpaque(false);
			accountPanel.setBorder(new EmptyBorder(4, 6, 4, 6));

			avatarLabel.setHorizontalAlignment(SwingConstants.CENTER);
			avatarLabel.setVerticalAlignment(SwingConstants.CENTER);
			avatarLabel.setOpaque(false);
			Dimension avatarSize = new Dimension(AVATAR_SIZE, AVATAR_SIZE);
			avatarLabel.setPreferredSize(avatarSize);
			avatarLabel.setMinimumSize(avatarSize);
			avatarLabel.setMaximumSize(new Dimension(AVATAR_SIZE, Integer.MAX_VALUE));

			usernameLabel.setFont(InstanceRowStyle.titleFont());
			usernameLabel.setOpaque(false);

			typeLabel.setFont(typeLabel.getFont().deriveFont(Font.PLAIN, SUBTITLE_FONT_SIZE));
			typeLabel.setOpaque(false);

			accountPanel.add(avatarLabel, BorderLayout.WEST);
			accountPanel.add(createTextPanel(), BorderLayout.CENTER);
			add(accountPanel, BorderLayout.CENTER);
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends SavedSession> list, SavedSession value, int index,
				boolean isSelected, boolean cellHasFocus) {
			usernameLabel.setText(value != null ? value.getUsername() : "");
			typeLabel.setText(getAccountTypeLabel(value));
				if (value != null && !AccountList.isOfflineAccount(value) && value.getAvatarImage() != null) {
					avatarLabel.setIcon(new ImageIcon(value.getAvatarImage()));
				} else {
					avatarLabel.setIcon(defaultAvatar);
				}

			Color background;
			Color primaryForeground;
			Color secondaryForeground;
			if (isSelected) {
				background = list.getSelectionBackground();
				primaryForeground = list.getSelectionForeground();
				secondaryForeground = primaryForeground;
			} else {
				background = list.getBackground();
				primaryForeground = list.getForeground();
				secondaryForeground = SwingHelper.uiColor("Label.disabledForeground", Color.DARK_GRAY);
			}

			setBackground(background);
			setForeground(primaryForeground);
			usernameLabel.setForeground(primaryForeground);
			typeLabel.setForeground(secondaryForeground);

			return this;
		}

		private JPanel createTextPanel() {
			JPanel textPanel = new JPanel(new GridBagLayout());
			textPanel.setOpaque(false);

			GridBagConstraints constraints = new GridBagConstraints();
			constraints.gridx = 0;
			constraints.gridy = 0;
			constraints.anchor = GridBagConstraints.WEST;
			constraints.weightx = 1.0;
			constraints.fill = GridBagConstraints.HORIZONTAL;
			textPanel.add(usernameLabel, constraints);

			constraints = (GridBagConstraints) constraints.clone();
			constraints.gridy = 1;
			constraints.insets = new Insets(TITLE_SUBTITLE_GAP, 0, 0, 0);
			textPanel.add(typeLabel, constraints);

			return textPanel;
		}

		private static String getAccountTypeLabel(SavedSession session) {
			if (session == null) {
				return "";
			}

			UserType type = session.getType();
			if (type == UserType.MICROSOFT) {
				return SharedLocale.tr("accounts.type.microsoft");
			} else if (type == UserType.OFFLINE) {
				return SharedLocale.tr("accounts.type.offline");
			} else if (type == UserType.MOJANG || type == UserType.LEGACY) {
				return SharedLocale.tr("accounts.type.mojang");
			}

			return "";
		}
	}
}
