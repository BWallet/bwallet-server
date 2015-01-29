package com.bdx.bwallet.server.web.controller.model;

public class SubscribeForm {

	private String publicMaster;
	private String after;
	private int lookAhead;
	private long firstIndex;

	public String getPublicMaster() {
		return publicMaster;
	}

	public void setPublicMaster(String publicMaster) {
		this.publicMaster = publicMaster;
	}

	public String getAfter() {
		return after;
	}

	public void setAfter(String after) {
		this.after = after;
	}

	public int getLookAhead() {
		return lookAhead;
	}

	public void setLookAhead(int lookAhead) {
		this.lookAhead = lookAhead;
	}

	public long getFirstIndex() {
		return firstIndex;
	}

	public void setFirstIndex(long firstIndex) {
		this.firstIndex = firstIndex;
	}
}
