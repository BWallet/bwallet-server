package com.bdx.bwallet.server.core.model;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.codehaus.jackson.map.annotate.JsonSerialize;

import com.bdx.bwallet.server.core.serialization.CustomDateJsonSerializer;

public class WalletAccoutStatus {

	private String status;
	private String publicMaster;
	private Date afterTimePoint;
	private long lookAhead;
	private long firstIndex;

	private List<UTXO> confirmed = new ArrayList<UTXO>();
	private List<UTXO> change = new ArrayList<UTXO>();
	private List<UTXO> receiving = new ArrayList<UTXO>();

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getPublicMaster() {
		return publicMaster;
	}

	public void setPublicMaster(String publicMaster) {
		this.publicMaster = publicMaster;
	}

	@JsonSerialize(using = CustomDateJsonSerializer.class)
	public Date getAfterTimePoint() {
		return afterTimePoint;
	}

	public void setAfterTimePoint(Date afterTimePoint) {
		this.afterTimePoint = afterTimePoint;
	}

	public long getLookAhead() {
		return lookAhead;
	}

	public void setLookAhead(long lookAhead) {
		this.lookAhead = lookAhead;
	}

	public long getFirstIndex() {
		return firstIndex;
	}

	public void setFirstIndex(long firstIndex) {
		this.firstIndex = firstIndex;
	}

	public List<UTXO> getConfirmed() {
		return confirmed;
	}

	public void setConfirmed(List<UTXO> confirmed) {
		this.confirmed = confirmed;
	}

	public List<UTXO> getChange() {
		return change;
	}

	public void setChange(List<UTXO> change) {
		this.change = change;
	}

	public List<UTXO> getReceiving() {
		return receiving;
	}

	public void setReceiving(List<UTXO> receiving) {
		this.receiving = receiving;
	}
}
