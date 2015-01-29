package com.bdx.bwallet.server.web.controller.model;

public class SendForm {

	private String transaction;
	private String transactionHash;
	private String publicMaster;
	
	public String getTransaction() {
		return transaction;
	}

	public void setTransaction(String transaction) {
		this.transaction = transaction;
	}

	public String getTransactionHash() {
		return transactionHash;
	}

	public void setTransactionHash(String transactionHash) {
		this.transactionHash = transactionHash;
	}

	public String getPublicMaster() {
		return publicMaster;
	}

	public void setPublicMaster(String publicMaster) {
		this.publicMaster = publicMaster;
	}

}
