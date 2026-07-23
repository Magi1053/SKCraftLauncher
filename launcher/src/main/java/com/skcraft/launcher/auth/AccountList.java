package com.skcraft.launcher.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.skcraft.launcher.dialog.component.ListListenerReducer;
import com.skcraft.launcher.persistence.Scrambled;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.apache.commons.lang.RandomStringUtils;

import javax.swing.*;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import java.util.List;

/**
 * Persisted account list
 */
@Scrambled("ACCOUNT_LIST_NOT_SECURITY!")
@Getter
@Setter
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class AccountList implements ListModel<SavedSession> {
	private List<SavedSession> accounts = Lists.newArrayList();
	private String clientId = RandomStringUtils.randomAlphanumeric(24);
	private String activeUuid;

	@JsonIgnore private final ListListenerReducer listeners = new ListListenerReducer();

	public synchronized void add(SavedSession session) {
		accounts.add(session);
		setActiveAccount(session);

		int index = accounts.size() - 1;
		listeners.intervalAdded(new ListDataEvent(this, ListDataEvent.INTERVAL_ADDED, index, index));
	}

	public synchronized void remove(SavedSession session) {
		int index = accounts.indexOf(session);

		if (index > -1) {
			boolean wasActive = session.getUuid() != null && session.getUuid().equals(activeUuid);
			accounts.remove(index);
			listeners.intervalRemoved(new ListDataEvent(this, ListDataEvent.INTERVAL_REMOVED, index, index));

			if (wasActive) {
				if (accounts.isEmpty()) {
					activeUuid = null;
				} else {
					setActiveAccount(accounts.get(0));
				}
			}
		}
	}

	public synchronized void update(SavedSession newSavedSession) {
		int index = accounts.indexOf(newSavedSession);

		if (index > -1) {
			accounts.set(index, newSavedSession);
			setActiveAccount(newSavedSession);
			listeners.contentsChanged(new ListDataEvent(this, ListDataEvent.CONTENTS_CHANGED, index, index));
		} else {
			this.add(newSavedSession);
		}
	}

	@JsonIgnore
	public synchronized SavedSession getActiveAccount() {
		if (Strings.isNullOrEmpty(activeUuid)) {
			return null;
		}

		for (SavedSession session : accounts) {
			if (activeUuid.equals(session.getUuid())) {
				return session;
			}
		}

		return null;
	}

	@JsonIgnore
	public synchronized SavedSession getOfflineAccount(String username) {
		for (SavedSession session : accounts) {
			if (isOfflineAccount(session) && username.equalsIgnoreCase(session.getUsername())) {
				return session;
			}
		}

		return null;
	}

	/**
	 * Insert or replace an offline profile keyed by username (not UUID), so a
	 * stale/wrong UUID cannot leave a duplicate entry behind.
	 */
	public synchronized void putOfflineAccount(SavedSession offlineAccount) {
		if (!isOfflineAccount(offlineAccount)) {
			throw new IllegalArgumentException("Not an offline account");
		}

		String username = offlineAccount.getUsername();
		for (int i = 0; i < accounts.size(); i++) {
			SavedSession existing = accounts.get(i);
			if (isOfflineAccount(existing) && username.equalsIgnoreCase(existing.getUsername())) {
				accounts.set(i, offlineAccount);
				setActiveAccount(offlineAccount);
				listeners.contentsChanged(new ListDataEvent(this, ListDataEvent.CONTENTS_CHANGED, i, i));
				return;
			}
		}

		add(offlineAccount);
	}

	public static boolean isOfflineAccount(SavedSession session) {
		return session != null && session.getType() == UserType.OFFLINE;
	}

	public synchronized boolean migrateActiveAccount() {
		if (getActiveAccount() != null) {
			return false;
		}

		if (!accounts.isEmpty()) {
			setActiveAccount(accounts.get(0));
			return true;
		}

		if (!Strings.isNullOrEmpty(activeUuid)) {
			activeUuid = null;
			return true;
		}

		return false;
	}

	public synchronized void setActiveAccount(SavedSession session) {
		if (session == null || session.getUuid() == null) {
			activeUuid = null;
		} else {
			activeUuid = session.getUuid();
		}
	}

	@Override
	public int getSize() {
		return accounts.size();
	}

	@Override
	public SavedSession getElementAt(int index) {
		return accounts.get(index);
	}

	@Override
	public void addListDataListener(ListDataListener l) {
		listeners.addListDataListener(l);
	}

	@Override
	public void removeListDataListener(ListDataListener l) {
		listeners.removeListDataListener(l);
	}
}
