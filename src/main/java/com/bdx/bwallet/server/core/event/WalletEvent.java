package com.bdx.bwallet.server.core.event;

import java.util.List;

import com.bdx.bwallet.server.core.WalletAccount;
import com.bdx.bwallet.server.core.model.UTXO;

public class WalletEvent {

	private WalletAccount account;
	
	private List<UTXO> confirmedUtxos;

	private List<UTXO> receivingUtxos;

	private List<UTXO> changeUtxos;

	public List<UTXO> getConfirmedUtxos() {
		return confirmedUtxos;
	}

	public void setConfirmedUtxos(List<UTXO> confirmedUtxos) {
		this.confirmedUtxos = confirmedUtxos;
	}

	public List<UTXO> getReceivingUtxos() {
		return receivingUtxos;
	}

	public void setReceivingUtxos(List<UTXO> receivingUtxos) {
		this.receivingUtxos = receivingUtxos;
	}

	public List<UTXO> getChangeUtxos() {
		return changeUtxos;
	}

	public void setChangeUtxos(List<UTXO> changeUtxos) {
		this.changeUtxos = changeUtxos;
	}

	public WalletAccount getAccount() {
		return account;
	}

	public void setAccount(WalletAccount account) {
		this.account = account;
	}
}
