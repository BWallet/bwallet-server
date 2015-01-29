package com.bdx.bwallet.server.core.event;

import java.util.List;

import com.bdx.bwallet.server.core.WalletChild;
import com.bdx.bwallet.server.core.model.UTXO;

public class WalletChildUpdateEvent extends WalletEvent {

	private WalletChild walletChild;

	private List<UTXO> confirmedChildUtxos;

	private List<UTXO> pendingChildUtxos;

	public WalletChildUpdateEvent(WalletChild walletChild, List<UTXO> confirmedChildUtxos, List<UTXO> pendingChildUtxos) {
		this.walletChild = walletChild;
		this.confirmedChildUtxos = confirmedChildUtxos;
		this.pendingChildUtxos = pendingChildUtxos;
	}

	public WalletChild getWalletChild() {
		return walletChild;
	}

	public List<UTXO> getConfirmedChildUtxos() {
		return confirmedChildUtxos;
	}

	public List<UTXO> getPendingChildUtxos() {
		return pendingChildUtxos;
	}

}
