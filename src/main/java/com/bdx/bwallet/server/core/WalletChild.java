package com.bdx.bwallet.server.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.binary.StringUtils;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.params.MainNetParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bdx.bwallet.server.core.event.WalletChildUpdateEvent;
import com.bdx.bwallet.server.core.model.TX;
import com.bdx.bwallet.server.core.model.UTXO;
import com.bdx.bwallet.server.core.model.WalletChildTypes;
import com.bdx.obelisk.client.Client;
import com.bdx.obelisk.client.Subscriber.Listener;
import com.bdx.obelisk.domain.History;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

public class WalletChild {

	final static Logger LOG = LoggerFactory.getLogger(WalletChild.class);
	
	final static String BLANK_HASH = "0000000000000000000000000000000000000000000000000000000000000000";
	
	final static long EIGHT_HOURS = 8 * 60 * 60 * 1000;
	
	final static long RENEW_INTERVAL = 1000 * 60 * 5;	// 5 minutes - server expired time is 10 minutes
	
	private WalletAccount account;
	
	private DeterministicKey key;

	private WalletChildTypes type;
	
	private Client obelisk;

	private BlockHeaderService blockHeaderService;
	
	private ThreadPoolExecutor executor;
	
	private Address address;

	private long[] keyPath;
	
	private ListenableFuture<Object> futureSubscribe;
	
	private ListenableFuture<Object> futureFetchHistory;

	private Map<String, Future<Object>> futureFetchTransactions = new ConcurrentHashMap<String, Future<Object>>();
	
	private volatile List<History> histories = null;
	
	private volatile boolean subscribed = false;

	private Map<String, Map<Integer, UTXO>> confirmedUtxos = new ConcurrentHashMap<String, Map<Integer, UTXO>>();	// one TX may have multiple UTXOs
	private Map<String, Map<Integer, UTXO>> pendingUtxos = new ConcurrentHashMap<String, Map<Integer, UTXO>>();
	
	private Map<String, TX> txs = new ConcurrentHashMap<String, TX>();
	
	private Lock lock = new ReentrantLock();
	
	private long timeout;
	
	private long initializeAt;
	
	private long lastRenew;
	
	private int retries = -1;
	
	private volatile boolean initialized = false;
	
	private SettableFuture<Boolean> futureInitialized;
	
	private Integer socketId = null;
	
	public WalletChild(WalletAccount account, DeterministicKey key, WalletChildTypes type, Client obelisk, BlockHeaderService blockHeaderService, ThreadPoolExecutor executor, long timeout) {
		this.account = account;
		this.key = key;
		this.type = type;
		this.obelisk = obelisk;
		this.blockHeaderService = blockHeaderService;
		this.executor = executor;
		this.timeout = timeout;
	}

	public void initialize() {
		this.reinitialize();
		futureInitialized = SettableFuture.create();
	}
	
	protected void onUpdate(Address addr, long height, byte[] blockHash, Transaction transaction) {
		List<UTXO> confirmed = new ArrayList<UTXO>();
		List<UTXO> pending = new ArrayList<UTXO>();
		
		if (LOG.isDebugEnabled())
			LOG.debug("receive update - address[" + key.getPathAsString() + " " + addr.toString() + "] transaction[" + transaction.getHashAsString() + "] height[" + height + "]");
		
		lock.lock();
		try {
			
			TX tx = txs.get(transaction.getHashAsString());
			if (tx != null) {
				tx.setHeight(height);
				
				// set block time
				this.setBlockTime(tx, height);
				
				if (LOG.isDebugEnabled())
					LOG.debug("update transaction[" + transaction.getHashAsString() + "] height to " + height);
			} else {
				tx = this.toTX(transaction, height);
				txs.put(transaction.getHashAsString(), tx);
				
				if (LOG.isDebugEnabled())
					LOG.debug("add new transaction " + transaction.getHashAsString());
			}
			account.addOrUpdateTx(tx);
			
			for (TransactionInput input : transaction.getInputs()) {
				String hash = input.getOutpoint().getHash().toString();
				int index = (int)input.getOutpoint().getIndex();
				Map<Integer, UTXO> utxos = confirmedUtxos.get(hash);
				if (utxos != null) {
					utxos.remove(index);
					if (utxos.isEmpty())
						confirmedUtxos.remove(hash);
				}
				// remove the pending UTXO if receive spend-updating before confirm-updating
				utxos = pendingUtxos.get(hash);
				if (utxos != null) {
					utxos.remove(index);
					if (utxos.isEmpty())
						pendingUtxos.remove(hash);
				}
			}
			
			if (height == 0) {
				for (TransactionOutput output : transaction.getOutputs()) {
					Address outAddr = output.getAddressFromP2PKHScript(MainNetParams.get());
					if (outAddr == null)
						outAddr = output.getAddressFromP2SH(MainNetParams.get());
					if (outAddr != null && outAddr.equals(addr)) {
						String txHash = transaction.getHashAsString();
						Map<Integer, UTXO> utxos = pendingUtxos.get(txHash);
						if (utxos == null) {
							utxos = new ConcurrentHashMap<Integer, UTXO>();
							pendingUtxos.put(txHash, utxos);
						}
						UTXO utxo = toUTXO(output, txHash);
						utxos.put(output.getIndex(), utxo);
					}
				}
			} else {
				for (TransactionOutput output : transaction.getOutputs()) {
					Address outAddr = output.getAddressFromP2PKHScript(MainNetParams.get());
					if (outAddr == null)
						outAddr = output.getAddressFromP2SH(MainNetParams.get());
					if (outAddr != null && outAddr.equals(addr)) {
						String txHash = transaction.getHashAsString();
						Map<Integer, UTXO> utxos = pendingUtxos.get(txHash);
						if (utxos != null && utxos.containsKey(output.getIndex())) {
							utxos.remove(output.getIndex());
						}
						utxos = confirmedUtxos.get(txHash);
						if (utxos == null) {
							utxos = new ConcurrentHashMap<Integer, UTXO>();
							confirmedUtxos.put(txHash, utxos);
						}
						UTXO utxo = toUTXO(output, txHash);
						utxos.put(output.getIndex(), utxo);
					}
				}
			}
			
			if (LOG.isDebugEnabled()) {
				LOG.debug("confirmed UTXOs in address[" + key.getPathAsString() + " " + addr.toString() + "] - " + confirmedUtxos);
				LOG.debug("pending UTXOs in address[" + key.getPathAsString() + " " + addr.toString() + "] - " + pendingUtxos);
			}
			
			for (Map<Integer, UTXO> map : confirmedUtxos.values()){
				if (map != null && map.values() != null) {
					confirmed.addAll(map.values());
				}
			}
			for (Map<Integer, UTXO> map : pendingUtxos.values()){
				if (map != null && map.values() != null) {
					pending.addAll(map.values());
				}
			}
		} finally {
			lock.unlock();
		}
		
		// update event and data snap-shoot
		WalletChildUpdateEvent event = new WalletChildUpdateEvent(this, confirmed, pending);
		event.setAccount(account);
		account.dispatchEvent(event);
	}
	
	protected void initializeSubscribe() {
		futureSubscribe = (ListenableFuture<Object>)obelisk.getSubscriber().subscribe(this.getAddress(), new Listener(){
			@Override
			public void onUpdate(final Address addr, final long height, final byte[] blockHash, final Transaction tx) {
				Futures.addCallback(futureInitialized, new FutureCallback<Object>() {
					public void onSuccess(Object result) {
						WalletChild.this.onUpdate(addr, height, blockHash, tx);
					}
					public void onFailure(Throwable thrown) {
					}
				}, executor);
			}
			@Override
			public void onSocketRemove(Integer socketId) {
				// resubscribe and set socketId for wallet child
				Runnable task = new Runnable() {
					@Override
					public void run() {
						ListenableFuture<Object> future = (ListenableFuture<Object>)obelisk.getSubscriber().subscribe(WalletChild.this.getAddress());
						for (int i = 0; i < 20; i++) {
							try {
								Integer socketId = (Integer)future.get(1000, TimeUnit.MILLISECONDS);
								WalletChild.this.socketId = socketId;
								break;
							} catch (InterruptedException | ExecutionException | TimeoutException e) {
								LOG.error(e.toString());
								future = (ListenableFuture<Object>)obelisk.getSubscriber().subscribe(WalletChild.this.getAddress());
							}
						}
					}
				};
				executor.submit(task);
			}
		});
		Futures.addCallback(futureSubscribe, new FutureCallback<Object>() {
			public void onSuccess(Object result) {
				if (LOG.isDebugEnabled())
					LOG.debug(key.getPathAsString() + " " + getAddress() + " subscribe success");
				socketId = (Integer)result;
				subscribed = true;
				WalletChild.this.initializeHistory();
			}
			public void onFailure(Throwable thrown) {
				LOG.error(thrown.toString());
				if (LOG.isDebugEnabled())
					LOG.debug(key.getPathAsString() + " " + getAddress() + " subscribe error or cancel");
			}
		}, executor);
	}
	
	protected void initializeHistory() {
		futureFetchHistory = (ListenableFuture<Object>) obelisk.getSubscriber().fetchHistory(
				WalletChild.this.getAddress(), 0);
		Futures.addCallback(futureFetchHistory, new FutureCallback<Object>() {
			@SuppressWarnings("unchecked")
			public void onSuccess(Object result) {
				if (LOG.isDebugEnabled())
					LOG.debug(key.getPathAsString() + " " + getAddress() + " fetchHistory success");
				lock.lock();
				try {
					histories = (List<History>) result;
					
					if (LOG.isDebugEnabled())
						LOG.debug(key.getPathAsString() + " " + getAddress() + " histories size " + histories.size());
					
					WalletChild.this.processHistories(histories);
					WalletChild.this.fetchTransactions();
					/*
					executor.submit(new Runnable() {
						@Override
						public void run() {
							WalletChild.this.fetchTransactions();
						}
					});
					*/
				} finally {
					lock.unlock();
				}
			}
			public void onFailure(Throwable thrown) {
				LOG.error(thrown.toString());
				if (LOG.isDebugEnabled())
					LOG.debug(key.getPathAsString() + " " + getAddress() + " fetchHistory error or cancel");
			}
		}, executor);
	}
	
	public void reinitialize() {
		initializeAt = System.currentTimeMillis();
		retries++;
		if (!subscribed) {
			if (futureSubscribe == null || futureSubscribe.cancel(true))
				this.initializeSubscribe();
		} else if (histories == null) {
			if (futureFetchHistory == null || futureFetchHistory.cancel(true))
				this.initializeHistory();
		} else {
			// Acquires the lock, insure initializeHistory onSuccess completed
			lock.lock();
			try {
				Iterator<String> iterator = futureFetchTransactions.keySet().iterator();
				while (iterator.hasNext()) {
					String hash = iterator.next();
					TX tx = txs.get(hash);
					if (tx != null && !tx.isInitialized()) {
						Future<Object> future = futureFetchTransactions.get(hash);
						if (future.cancel(true)) {
							
							if (LOG.isDebugEnabled())
								LOG.debug("re-fetch transaction[" + hash + "] height[" + tx.getHeight() + "]" );
							
							if (tx.getHeight() == 0)
								future = obelisk.getTransactionPool().fetchTransaction(hash);
							else
								future = obelisk.getBlockchain().fetchTransaction(hash);
							futureFetchTransactions.put(hash, future);
						}
					}
				}
				this.fetchTransactions();
				/*
				executor.submit(new Runnable() {
					@Override
					public void run() {
						WalletChild.this.fetchTransactions();
					}
				});
				*/
			} finally {
				lock.unlock();
			}
		}
	}
	
	public Future<Object> renew(boolean force) {
		long now = System.currentTimeMillis();
		SettableFuture<Object> future = null;
		if (force || now - this.lastRenew >= RENEW_INTERVAL) {
			future = (SettableFuture<Object>)obelisk.getSubscriber().renew(getAddress(), socketId);
			Futures.addCallback(future, new FutureCallback<Object>() {
				public void onSuccess(Object result) {
					LOG.debug(key.getPathAsString() + " " + getAddress() + " renew success");
					lastRenew = System.currentTimeMillis();
				}
				public void onFailure(Throwable thrown) {
					LOG.error(thrown.toString());
					LOG.debug(key.getPathAsString() + " " + getAddress() + " renew error or cancel");
				}
			});
		}
		return future;
	}
	
	public void destroy() {
		obelisk.getSubscriber().unsubscribe(getAddress(), this.socketId);
	}
	
	protected void fetchTransactions() {
		Iterator<String> iterator = futureFetchTransactions.keySet().iterator();
		while (iterator.hasNext()) {
			String hash = iterator.next();
			Future<Object> future = futureFetchTransactions.get(hash);
			Transaction transaction = null;
			try {
				transaction = (Transaction)future.get(2000, TimeUnit.MILLISECONDS);
			} catch (InterruptedException | ExecutionException | TimeoutException e) {
				LOG.error("transaction[" + hash + "] fetch error - " + e.toString());
			}
			if (transaction != null) {
				
				if (LOG.isDebugEnabled())
					LOG.debug("transaction[" + hash + "] fetch succeed");
				
				futureFetchTransactions.remove(hash);
				
				TX tx = txs.get(hash);
				if (tx != null) {
					tx.initialize(transaction, getAddress(), getKeyPath());
				}
				
				Map<Integer, UTXO> utxos = confirmedUtxos.get(hash);
				if (utxos == null)
					utxos = pendingUtxos.get(hash);
				if (utxos != null) {
					for (UTXO utxo : utxos.values()) {
						TransactionOutput out = transaction.getOutput((int)utxo.getIx());
						utxo.setScript(StringUtils.newStringUtf8(Base64.encodeBase64(out.getScriptBytes())));
						// blockTime unused in web wallet
					}
				}
			}
		}
		if (futureFetchTransactions.isEmpty()) {
			
			if (LOG.isDebugEnabled())
				LOG.debug("transactions in address[" + key.getPathAsString() + " " + getAddress().toString() + "] fetch completed");
			
			for (TX tx : txs.values()) {
				account.addOrUpdateTx(tx);
			}
			this.initialized = true;
			this.lastRenew = System.currentTimeMillis();
			futureInitialized.set(true);
			
			if (LOG.isDebugEnabled())
				LOG.debug("address[" + key.getPathAsString() + " " + getAddress().toString() + "] initialized");
		}
	}
	
	protected void processHistories(List<History> histories) {
		if (histories != null) {
			for (History history : histories) {
				String oHash = Hex.encodeHexString(history.getOutput().getHash());
				String sHash = Hex.encodeHexString(history.getSpend().getHash());
				
				// preprocess block header
				if (history.getOutputHeight() != 0)
					blockHeaderService.preprocess(history.getOutputHeight());
				
				this.addAndRequestTransactionIfNecessary(history.getOutputHeight(), oHash);
				
				if (BLANK_HASH.equals(sHash)) {
					UTXO utxo = new UTXO();
					utxo.setIx(history.getOutput().getIndex());
					utxo.setKeyPathForAddress(this.getKeyPath());
					utxo.setTransactionHash(oHash);
					utxo.setValue(BigInteger.valueOf(history.getValue()));
					
					if (history.getOutputHeight() == 0) {
						Map<Integer, UTXO> utxos = pendingUtxos.get(oHash);
						if (utxos == null) {
							utxos =  new ConcurrentHashMap<Integer, UTXO>();
							pendingUtxos.put(oHash, utxos);
						}
						utxos.put((int)utxo.getIx(), utxo);
					} else {
						Map<Integer, UTXO> utxos = confirmedUtxos.get(oHash);
						if (utxos == null) {
							utxos =  new ConcurrentHashMap<Integer, UTXO>();
							confirmedUtxos.put(oHash, utxos);
						}
						utxos.put((int)utxo.getIx(), utxo);
					}
				} else {
					// preprocess block header
					if (history.getSpendHeight() != 0)
						blockHeaderService.preprocess(history.getSpendHeight());
					
					this.addAndRequestTransactionIfNecessary(history.getSpendHeight(), sHash);
				}
			}
		}
		
		if (LOG.isDebugEnabled()) {
			LOG.debug("confirmed UTXOs in address[" + key.getPathAsString() + " " + getAddress().toString() + "] - " + confirmedUtxos);
			LOG.debug("pending UTXOs in address[" + key.getPathAsString() + " " + getAddress().toString() + "] - " + pendingUtxos);
		}
		
		if (LOG.isDebugEnabled())
			LOG.debug("transactions in address[" + key.getPathAsString() + " " + getAddress() + "] to fetch size is " + futureFetchTransactions.size());
	}

	protected void addAndRequestTransactionIfNecessary(long height, String txHash) {
		if (!txs.containsKey(txHash)) {
			TX tx = new TX(txHash, height);
			
			// set block time
			this.setBlockTime(tx, height);
			
			txs.put(txHash, tx);
			
			Future<Object> future = null;
			if (height == 0)
				future = obelisk.getTransactionPool().fetchTransaction(txHash);
			else
				future = obelisk.getBlockchain().fetchTransaction(txHash);
			
			futureFetchTransactions.put(txHash, future);
		}
	}

	protected TX toTX(Transaction transaction, long height){
		TX tx = new TX(transaction.getHashAsString(), height);
		
		// set block time
		this.setBlockTime(tx, height);
		
		tx.initialize(transaction, getAddress(), getKeyPath());
		return tx;
	}
	
	protected void setBlockTime(TX tx, long height) {
		if (height != 0)
			tx.setBlockTime(new Date(blockHeaderService.getBlockHeader(height).getTimestamp() * 1000 - EIGHT_HOURS));
		else 
			tx.setBlockTime(new Date(new Date().getTime() - EIGHT_HOURS));
	}
	
	protected UTXO toUTXO(TransactionOutput output, String txHash){
		UTXO utxo = new UTXO();
		utxo.setIx(output.getIndex());
		utxo.setKeyPathForAddress(this.getKeyPath());
		utxo.setScript(StringUtils.newStringUtf8(Base64.encodeBase64(output.getScriptBytes())));
		utxo.setTransactionHash(txHash);
		utxo.setValue(BigInteger.valueOf(output.getValue().getValue()));
		return utxo;
	}
	
	public boolean isInitialized() {
		return initialized;
	}

	public DeterministicKey getKey() {
		return key;
	}

	public Address getAddress() {
		if (address == null)
			address = key.toAddress(MainNetParams.get());
		;
		return address;
	}

	public List<History> getHistories() {
		return histories;
	}

	public int getRetries() {
		return retries;
	}

	public Map<String, Map<Integer, UTXO>> getConfirmedUtxos() {
		return confirmedUtxos;
	}

	public Map<String, Map<Integer, UTXO>> getPendingUtxos() {
		return pendingUtxos;
	}
	
	public boolean isTimeout() {
		long now = System.currentTimeMillis();
		if (now - initializeAt > timeout)
			return true;
		else
			return false;
	}
	
	public long[] getKeyPath() {
		if (this.keyPath == null) {
			long[] keyPath = new long[key.getPath().size() - 1];
			for (int i = 1; i < key.getPath().size(); i++) {
				ChildNumber childNumber = key.getPath().get(i);
				keyPath[i - 1] = childNumber.getI();
			}
			this.keyPath = keyPath;
		}
		return this.keyPath;
	}

	public WalletChildTypes getType() {
		return type;
	}
}
