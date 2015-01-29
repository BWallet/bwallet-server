package com.bdx.bwallet.server.core;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bdx.bwallet.server.core.event.WalletEvent;
import com.bdx.bwallet.server.core.event.WalletEventListener;

public class WalletConnection implements WalletEventListener{

	final static Logger LOG = LoggerFactory.getLogger(WalletConnection.class);
	
	private String clientId;

	private long lastPoll;
	
	private BlockingQueue<WalletEvent> events = new LinkedBlockingQueue<WalletEvent>();
	
	private Set<WalletAccount> accounts = Collections.synchronizedSet(new HashSet<WalletAccount>());
	
	public WalletConnection(String clientId){
		this.clientId = clientId;
		this.lastPoll = System.currentTimeMillis();
	}
	
	public String getClientId() {
		return clientId;
	}

	public boolean isDead() {
		return System.currentTimeMillis() - lastPoll > 1000 * 60 * 2;
	}

	@Override
	public void onEvent(WalletEvent event) {
		events.add(event);
	}
	
	public WalletEvent pollEvent() {
		this.lastPoll = System.currentTimeMillis();
		WalletEvent event = events.poll();
		return event;
	}
	
	public WalletEvent pollEvent(long timeout, TimeUnit unit) {
		this.lastPoll = System.currentTimeMillis();
		WalletEvent event = null;
		try {
			event = events.poll(timeout, unit);
		} catch (InterruptedException e) {
			LOG.error(e.toString());
		}
		return event;
	}
	
	public WalletEvent pollEvent(WalletAccount account) {
		this.lastPoll = System.currentTimeMillis();
		// WalletEvent event = events.poll();
		// peek a event and ensure that it's coming from this account, then remove it to ensure that it be polled only one time.
		WalletEvent event = events.peek();
		if (event != null && event.getAccount().equals(account)) {
			if (events.remove(event))
				return event;
			else
				return null;
		} else
			return null;
	}

	public Set<WalletAccount> getAccounts() {
		return accounts;
	}

	public void addAccount(WalletAccount account) {
		accounts.add(account);
	}
}
