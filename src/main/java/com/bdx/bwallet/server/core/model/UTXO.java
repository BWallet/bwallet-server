package com.bdx.bwallet.server.core.model;

import java.math.BigInteger;
import java.util.Date;

public class UTXO {

	private BigInteger value;
	private String script;
	private long ix;
	private String transactionHash;
	private long[] keyPathForAddress;
	/**
	 * unused in web wallet
	 */
	@Deprecated
	private Date blockTime;

	public BigInteger getValue() {
		return value;
	}

	public void setValue(BigInteger value) {
		this.value = value;
	}

	public String getScript() {
		return script;
	}

	public void setScript(String script) {
		this.script = script;
	}

	public long getIx() {
		return ix;
	}

	public void setIx(long ix) {
		this.ix = ix;
	}

	public String getTransactionHash() {
		return transactionHash;
	}

	public void setTransactionHash(String transactionHash) {
		this.transactionHash = transactionHash;
	}

	public long[] getKeyPathForAddress() {
		return keyPathForAddress;
	}

	public void setKeyPathForAddress(long[] keyPathForAddress) {
		this.keyPathForAddress = keyPathForAddress;
	}

	public Date getBlockTime() {
		return blockTime;
	}

	public void setBlockTime(Date blockTime) {
		this.blockTime = blockTime;
	}

}
