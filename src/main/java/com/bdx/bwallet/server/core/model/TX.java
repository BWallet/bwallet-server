package com.bdx.bwallet.server.core.model;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.StringUtils;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.params.MainNetParams;
import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.codehaus.jackson.map.annotate.JsonSerialize;

import com.bdx.bwallet.server.core.serialization.CustomDateJsonSerializer;

@JsonIgnoreProperties({"original", "initialized"})
public class TX {

	private long lockTime;

	/**
	 * unused in web wallet
	 */
	@Deprecated
	private String blockHash;

	private long height;

	private long version;

	private String hash;

	private Date blockTime;

	private List<Input> inputs = new ArrayList<Input>();

	private List<Output> outputs = new ArrayList<Output>();

	private Transaction original;

	public TX(){
	}
	
	public TX(String hash, long height){
		this.hash = hash;
		this.height = height;
	}
	
	public Transaction getOriginal() {
		return original;
	}

	public void initialize(Transaction original, Address address, long[] keyPath) {
		this.lockTime = original.getLockTime();
		this.version = original.getVersion();
		
		for (TransactionInput input : original.getInputs()) {
			Input i = new Input();
			i.setSourceHash(input.getOutpoint().getHash().toString());
			i.setIx(input.getOutpoint().getIndex());
			i.setScript(StringUtils.newStringUtf8(Base64.encodeBase64(input.getScriptBytes())));
			i.setSequence(input.getSequenceNumber());
			inputs.add(i);
		}
		for (TransactionOutput output : original.getOutputs()) {
			Address addr = output.getAddressFromP2PKHScript(MainNetParams.get());
			if (addr == null)
				addr = output.getAddressFromP2SH(MainNetParams.get());
			Output o = new Output();
			o.setAddress(addr);
			if (addr.equals(address))
				o.setKeyPathForAddress(keyPath);
			o.setValue(BigInteger.valueOf(output.getValue().getValue()));
			o.setScript(StringUtils.newStringUtf8(Base64.encodeBase64(output.getScriptBytes())));
			o.setIx(output.getIndex());
			o.setTransactionHash(original.getHashAsString());
			outputs.add(o);
		}
		this.original = original;
	}
	
	public boolean isInitialized() {
		return original != null;
	}
	
	public long getLockTime() {
		return lockTime;
	}

	public void setLockTime(long lockTime) {
		this.lockTime = lockTime;
	}

	public String getBlockHash() {
		return blockHash;
	}

	public void setBlockHash(String blockHash) {
		this.blockHash = blockHash;
	}

	public long getHeight() {
		return height;
	}

	public void setHeight(long height) {
		this.height = height;
	}

	public long getVersion() {
		return version;
	}

	public void setVersion(long version) {
		this.version = version;
	}

	public String getHash() {
		return hash;
	}

	public void setHash(String hash) {
		this.hash = hash;
	}

	@JsonSerialize(using = CustomDateJsonSerializer.class)
	public Date getBlockTime() {
		return blockTime;
	}

	public void setOriginal(Transaction original) {
		this.original = original;
	}

	public void setBlockTime(Date blockTime) {
		this.blockTime = blockTime;
	}

	public List<Input> getInputs() {
		return inputs;
	}

	public List<Output> getOutputs() {
		return outputs;
	}

	public static class Input {
		private String sourceHash;
		private long ix;
		private String script;
		private long sequence;

		public String getSourceHash() {
			return sourceHash;
		}

		public void setSourceHash(String sourceHash) {
			this.sourceHash = sourceHash;
		}

		public long getIx() {
			return ix;
		}

		public void setIx(long ix) {
			this.ix = ix;
		}

		public String getScript() {
			return script;
		}

		public void setScript(String script) {
			this.script = script;
		}

		public long getSequence() {
			return sequence;
		}

		public void setSequence(long sequence) {
			this.sequence = sequence;
		}
	}

	@JsonIgnoreProperties({"address"})
	public static class Output {
		private long[] keyPathForAddress = null;
		private BigInteger value;
		private String script;
		private long ix;
		private String transactionHash;

		private Address address;
		
		public long[] getKeyPathForAddress() {
			return keyPathForAddress;
		}

		public void setKeyPathForAddress(long[] keyPathForAddress) {
			this.keyPathForAddress = keyPathForAddress;
		}

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

		public Address getAddress() {
			return address;
		}

		public void setAddress(Address address) {
			this.address = address;
		}
	}

}
