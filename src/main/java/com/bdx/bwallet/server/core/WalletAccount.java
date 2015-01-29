package com.bdx.bwallet.server.core;

import java.lang.reflect.InvocationTargetException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicHierarchy;
import org.bitcoinj.crypto.DeterministicKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bdx.bwallet.server.core.event.WalletChildUpdateEvent;
import com.bdx.bwallet.server.core.event.WalletEvent;
import com.bdx.bwallet.server.core.event.WalletInitializedEvent;
import com.bdx.bwallet.server.core.model.TX;
import com.bdx.bwallet.server.core.model.UTXO;
import com.bdx.bwallet.server.core.model.WalletAccoutStatus;
import com.bdx.bwallet.server.core.model.WalletChildTypes;
import com.bdx.obelisk.client.Client;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;

public class WalletAccount {

	final static Logger LOG = LoggerFactory.getLogger(WalletAccount.class);
	
	final static int INDEX_OF_EXTERNAL = 0;
	final static int INDEX_OF_INTERNAL = 1;
	
	private DeterministicKey masterKey;
	
	/**
	 * we need to keep the serialization of master key, we have not the parent so deserializion will change same additional data of the master key 
	 * e.g xpub6BmuF6piZw28UTSuuhRGD96EGiRnp9oihRXPu93aYRd1NUzytTDiqUM8s3UEKttnxXvqAFWZrmPGz9MzLYMC57TATXugRNtuCHdeYRUxYZZ ->
	 * xpub67tVq9TLPPoaHnRMMxHvgKS4T8odMJr33KPMM5LjXunZVRsjkD5RJH6b2HARBNP1ck3gjA9Q9ZAgHsxWuGrDFEajQdKp23yLRd6oYf67Ref
	 */
	private String masterKeySerialization;
	private String after;
	private int lookAhead; // 20
	private long firstIndex; // 0

	private volatile int externalCurrentIndex;
	private volatile int externalLastNotEmptyIndex;
	
	private volatile int internalCurrentIndex;
	private volatile int internalLastNotEmptyIndex;
	
	private DeterministicHierarchy masterKeyHierarchy;

	private Set<WalletConnection> connections = Collections.synchronizedSet(new HashSet<WalletConnection>());

	private WalletAccoutStatus status;

	private Client obelisk;

	private BlockHeaderService blockHeaderService;
	
	private ThreadPoolExecutor executor;
	
	//private List<WalletChild> externalChilds = Collections.synchronizedList(new ArrayList<WalletChild>());
	//private List<WalletChild> internalChilds = Collections.synchronizedList(new ArrayList<WalletChild>());

	private Map<DeterministicKey, WalletChild> externalChilds = new ConcurrentHashMap<DeterministicKey, WalletChild>();
	private Map<DeterministicKey, WalletChild> internalChilds = new ConcurrentHashMap<DeterministicKey, WalletChild>();
	
	private List<ChildNumber> externalPath = Arrays.asList(new ChildNumber[] { new ChildNumber(INDEX_OF_EXTERNAL, false) });
	private List<ChildNumber> internalPath = Arrays.asList(new ChildNumber[] { new ChildNumber(INDEX_OF_INTERNAL, false) });

	private Lock externalLock = new ReentrantLock();
	private Lock internalLock = new ReentrantLock();
	
	private Lock utxosLock = new ReentrantLock();
	
	private SettableFuture<Boolean> futureInitialized;
	
	private Map<WalletChild, List<UTXO>> confirmedUtxos = new ConcurrentHashMap<WalletChild, List<UTXO>>();		// one TX may have multiple UTXOs?
	private Map<WalletChild, List<UTXO>> receivingUtxos = new ConcurrentHashMap<WalletChild, List<UTXO>>();
	private Map<WalletChild, List<UTXO>> changeUtxos = new ConcurrentHashMap<WalletChild, List<UTXO>>();
	
	private Map<String, TX> txs = new ConcurrentHashMap<String, TX>();
	private Lock txsLock = new ReentrantLock();
	
	private Lock eventLock = new ReentrantLock();
	
	private Lock initializeLock = new ReentrantLock();
	
	/**
	 * -1 - initialize failed
	 *  0 - initializing
	 *  1 - initialize successful
	 */
	private AtomicInteger initializeStatus = new AtomicInteger(-1);
	
	public WalletAccount(DeterministicKey masterKey, String masterKeySerialization, Client obelisk, BlockHeaderService blockHeaderService, final ThreadPoolExecutor executor, String after, int lookAhead, long firstIndex) {
		assert masterKey != null;
		assert obelisk != null;
		assert executor != null;
		
		this.masterKey = masterKey;
		this.masterKeySerialization = masterKeySerialization;
		this.obelisk = obelisk;
		this.blockHeaderService = blockHeaderService;
		this.executor = executor;
		
		this.after = after;
		this.lookAhead = lookAhead;
		this.firstIndex = firstIndex;
		
		masterKeyHierarchy = new DeterministicHierarchy(masterKey);
		WalletAccoutStatus status = new WalletAccoutStatus();
		status.setPublicMaster(this.masterKeySerialization);
		status.setLookAhead(this.lookAhead);
		status.setFirstIndex(this.firstIndex);
		status.setStatus("PENDING");
		try {
			status.setAfterTimePoint(DateUtils.parseDate(this.after, "yyyy-MM-dd"));
		} catch (ParseException e) {
			LOG.error(e.toString());
		}
		this.status = status;
		
		futureInitialized = SettableFuture.create();
	}

	public void addOrUpdateTx(TX tx){
		txsLock.lock();
		try {
			TX txInTxs = txs.get(tx.getHash());
			if (txInTxs == null) {
				txs.put(tx.getHash(), tx);
				
				if (LOG.isDebugEnabled())
					LOG.debug("add new transaction[" + tx.getHash() + "] in account[" + this.masterKeySerialization + "]");
			} else {
				// merge tx's keyPathForAddress
				for (int i = 0; i < tx.getOutputs().size(); i++) {
					TX.Output o = tx.getOutputs().get(i);
					if (o.getKeyPathForAddress() != null) {
						TX.Output oInTxs = txInTxs.getOutputs().get(i);
						if (oInTxs != null && oInTxs.getKeyPathForAddress() == null) {
							oInTxs.setKeyPathForAddress(o.getKeyPathForAddress());
						}
					}
				}
				
				txInTxs.setHeight(tx.getHeight());
				txInTxs.setBlockTime(tx.getBlockTime());
				
				if (LOG.isDebugEnabled())
					LOG.debug("update transaction[" + tx.getHash() + "] to height[" + tx.getHeight() + "] in account[" + this.masterKeySerialization + "]");
			}
		} finally {
			txsLock.unlock();
		}
	}
	
	public List<TX> getTXs(){
		List<TX> txList = new ArrayList<TX>();
		txsLock.lock();
		try {
			for (TX txInTxs : txs.values()) {
				TX tx = this.cloneTX(txInTxs);
				txList.add(tx);
			}
		} finally {
			txsLock.unlock();
		}
		return txList;
	}
	
	public TX getTX(String hash){
		TX tx = null;
		txsLock.lock();
		try {
			TX txInTxs = txs.get(hash);
			if (txInTxs != null)
				tx = this.cloneTX(txInTxs);
		} finally {
			txsLock.unlock();
		}
		return tx;
	}
	
	protected TX cloneTX(TX source) {
		TX tx = new TX();
		org.springframework.beans.BeanUtils
				.copyProperties(source, tx, new String[] { "original", "inputs", "outputs" });
		for (TX.Input is : source.getInputs()) {
			TX.Input i = new TX.Input();
			org.springframework.beans.BeanUtils.copyProperties(is, i, new String[] {});
			tx.getInputs().add(i);
		}
		for (TX.Output os : source.getOutputs()) {
			TX.Output o = new TX.Output();
			org.springframework.beans.BeanUtils.copyProperties(os, o, new String[] { "address" });
			tx.getOutputs().add(o);
		}
		return tx;
	}
	
	protected void snapshootUTXOs(List<UTXO> confirmedSnapshoot, List<UTXO> pendingSnapshoot, List<UTXO> changeSnapshoot) {
		utxosLock.lock();
		try {
			for (List<UTXO> utxos : confirmedUtxos.values()) {
				confirmedSnapshoot.addAll(utxos);
			}
			for (List<UTXO> utxos : receivingUtxos.values()) {
				pendingSnapshoot.addAll(utxos);
			}
			for (List<UTXO> utxos : changeUtxos.values()) {
				changeSnapshoot.addAll(utxos);
			}
		} finally {
			utxosLock.unlock();
		}
	}
	
	protected void updateUTXOs(WalletChild walletChild, List<UTXO> confirmedChildUtxos, List<UTXO> pendingChildUtxos){
		utxosLock.lock();
		try {
			confirmedUtxos.put(walletChild, confirmedChildUtxos);
			if (walletChild.getType() == WalletChildTypes.EXTERNAL)
				receivingUtxos.put(walletChild, pendingChildUtxos);
			else
				changeUtxos.put(walletChild, pendingChildUtxos);
		} finally {
			utxosLock.unlock();
		}
	}
	
	// TODO rename this method
	protected void prepareWalletUpdate(WalletChildUpdateEvent event){
		DeterministicKey key = event.getWalletChild().getKey();
		int path = key.getPath().get(key.getPath().size() - 2).getI();
		int index = key.getChildNumber().getI();
		if (INDEX_OF_EXTERNAL == path) {
			externalLock.lock();
			try {
				if (externalLastNotEmptyIndex < index) {
					externalLastNotEmptyIndex = index;
					InitializedPathSummary summary = WalletAccount.this.initializePath(WalletChildTypes.EXTERNAL, externalPath, externalChilds,
							externalCurrentIndex + 1, lookAhead - (externalCurrentIndex - externalLastNotEmptyIndex), externalLastNotEmptyIndex);
					externalCurrentIndex = summary.getCurrentIndex();
					externalLastNotEmptyIndex = summary.getLastNotEmptyIndex();
					
					if (LOG.isDebugEnabled())
						LOG.debug("listening external path in account[" + this.masterKeySerialization
								+ "] is updated - externalCurrentIndex[" + externalCurrentIndex
								+ "] externalLastNotEmptyIndex[" + externalLastNotEmptyIndex + "]");
				}
			} finally {
				externalLock.unlock();
			}
		} else {
			internalLock.lock();
			try {
				if (internalLastNotEmptyIndex < index) {
					internalLastNotEmptyIndex = index;
					InitializedPathSummary summary = WalletAccount.this.initializePath(WalletChildTypes.INTERNAL, internalPath, internalChilds,
							internalCurrentIndex + 1, 1, internalLastNotEmptyIndex);
					internalCurrentIndex = summary.getCurrentIndex();
					internalLastNotEmptyIndex = summary.getLastNotEmptyIndex();
					
					if (LOG.isDebugEnabled())
						LOG.debug("listening internal path in account[" + this.masterKeySerialization
								+ "] is updated - internalCurrentIndex[" + internalCurrentIndex
								+ "] internalLastNotEmptyIndex[" + internalLastNotEmptyIndex + "]");
				}
			} finally {
				internalLock.unlock();
			}
		}
	}
	
	public void dispatchEvent(final WalletEvent event) {
		if (event != null) {
			Futures.addCallback(futureInitialized, new FutureCallback<Object>() {
				public void onSuccess(Object result) {
					eventLock.lock();
					try {
						if (event instanceof WalletChildUpdateEvent) {
							// TODO move this codes to new method
							final WalletChildUpdateEvent e = (WalletChildUpdateEvent) event;

							if (LOG.isDebugEnabled())
								LOG.debug("receive event from address[" + e.getWalletChild().getAddress() + "]");
							
							WalletAccount.this.updateUTXOs(e.getWalletChild(), e.getConfirmedChildUtxos(), e.getPendingChildUtxos());
							
							List<UTXO> confirmedSnapshoot = new ArrayList<UTXO>();
							List<UTXO> receivingSnapshoot = new ArrayList<UTXO>();
							List<UTXO> changeSnapshoot = new ArrayList<UTXO>();
							WalletAccount.this.snapshootUTXOs(confirmedSnapshoot, receivingSnapshoot, changeSnapshoot);
							e.setConfirmedUtxos(confirmedSnapshoot);
							e.setReceivingUtxos(receivingSnapshoot);
							e.setChangeUtxos(changeSnapshoot);
							
							Runnable task = new Runnable() {
								@Override
								public void run() {
									prepareWalletUpdate(e);
								}
							};
							executor.submit(task);
						}
						for (WalletConnection connection : connections) {
							connection.onEvent(event);
						}
					} finally {
						eventLock.unlock();
					}
				}
				public void onFailure(Throwable thrown) {
				}
			}, executor);
		}
	}
	
	public Future<Void> initialize() {
		final SettableFuture<Void> future = SettableFuture.create();
		
		if (initializeStatus.get() != -1) {
			future.set(null);
			return future;
		}
		
		Runnable task = new Runnable(){
			@Override
			public void run() {
				if (initializeLock.tryLock()) {
					try {
						initializeStatus.set(0);
						
						InitializedPathSummary externalSummary = WalletAccount.this.initializePath(WalletChildTypes.EXTERNAL, externalPath,
								externalChilds, (int) firstIndex, lookAhead, (int) firstIndex - 1);
						externalCurrentIndex = externalSummary.getCurrentIndex();
						externalLastNotEmptyIndex = externalSummary.getLastNotEmptyIndex();
						
						if (LOG.isDebugEnabled())
							LOG.debug("listening external path in account[" + masterKeySerialization
									+ "] is done - externalCurrentIndex[" + externalCurrentIndex
									+ "] externalLastNotEmptyIndex[" + externalLastNotEmptyIndex + "]");
						
						InitializedPathSummary internalSummary = WalletAccount.this.initializePath(WalletChildTypes.INTERNAL, internalPath,
								internalChilds, 0, 1, -1);
						internalCurrentIndex = internalSummary.getCurrentIndex();
						internalLastNotEmptyIndex = internalSummary.getLastNotEmptyIndex();
						
						if (LOG.isDebugEnabled())
							LOG.debug("listening internal path in account[" + masterKeySerialization
									+ "] is done - internalCurrentIndex[" + internalCurrentIndex
									+ "] internalLastNotEmptyIndex[" + internalLastNotEmptyIndex + "]");
						
						WalletAccount.this.collectUTXOs(new ArrayList<WalletChild>(externalChilds.values()));
						WalletAccount.this.collectUTXOs(new ArrayList<WalletChild>(internalChilds.values()));
						WalletAccount.this.status.setStatus("SYNCHRONIZED");
						
						if (LOG.isDebugEnabled()) {
							LOG.debug("confirmed UTXOs in account[" + masterKeySerialization + "] - " + confirmedUtxos);
							LOG.debug("receiving UTXOs in account[" + masterKeySerialization + "] - " + receivingUtxos);
							LOG.debug("change UTXOs in account[" + masterKeySerialization + "] - " + changeUtxos);
						}
						
						futureInitialized.set(true);
						initializeStatus.set(1);
						
						if (LOG.isDebugEnabled())
							LOG.debug("account[" + masterKeySerialization + "] initialize completed");
					} catch (Exception e) {
						initializeStatus.set(-1);
					} finally {
						initializeLock.unlock();
						future.set(null);
					}
				} else {
					future.set(null);
				}
			}
		};
		
		executor.execute(task);
		return future;
	}

	private void collectUTXOs(List<WalletChild> walletChilds){
		for (WalletChild walletChild : walletChilds){
			if (walletChild.getConfirmedUtxos().size() > 0) {
				for (Map<Integer, UTXO> utxos : walletChild.getConfirmedUtxos().values()) {
					// new ArrayList will copy the utxos.values(), so changing in walletChild would not reflect in it.
					// we reflect the changing using event only.
					if (utxos.size() > 0) {
						List<UTXO> childUtxos = confirmedUtxos.get(walletChild);
						if (childUtxos == null) {
							childUtxos = new ArrayList<UTXO>();
							confirmedUtxos.put(walletChild, childUtxos);
						}
						childUtxos.addAll(utxos.values());
						//confirmedUtxos.put(walletChild, new ArrayList<UTXO>(utxos.values()));
					}
				}
			}
			if (walletChild.getPendingUtxos().size() > 0) {
				for (Map<Integer, UTXO> utxos : walletChild.getPendingUtxos().values()) {
					if (utxos.size() > 0) {
						List<UTXO> childUtxos = null;
						if (walletChild.getType() == WalletChildTypes.EXTERNAL) {
							childUtxos = receivingUtxos.get(walletChild);
							if (childUtxos == null) {
								childUtxos = new ArrayList<UTXO>();
								receivingUtxos.put(walletChild, childUtxos);
							}
							//receivingUtxos.put(walletChild, new ArrayList<UTXO>(utxos.values()));
						} else {
							childUtxos = changeUtxos.get(walletChild);
							if (childUtxos == null) {
								childUtxos = new ArrayList<UTXO>();
								changeUtxos.put(walletChild, childUtxos);
							}
							//changeUtxos.put(walletChild, new ArrayList<UTXO>(utxos.values()));
						}
						childUtxos.addAll(utxos.values());
					}
				}
			}
		}
	}
	
	protected InitializedPathSummary initializePath(WalletChildTypes type, List<ChildNumber> path, Map<DeterministicKey, WalletChild> childs, int firstIndex, int lookAhead, int lastNotEmptyIndex){
		// we don't care hardened key, hardened master pub key can't derive child pub key
		int start_index = firstIndex;
		int current_index = start_index - 1;
		int last_not_empty_index = lastNotEmptyIndex;
		int step_length = lookAhead;
		while (true) {
			List<WalletChild> batchChilds = this.batchChildInitialize(type, path, start_index,
					step_length, childs);
			while (true) {
				int initialized = 0;
				for (WalletChild walletChild : batchChilds) {
					current_index++;
					if (!walletChild.isInitialized()) {
						if (walletChild.isTimeout()) {
							if (walletChild.getRetries() > 20) {
								LOG.error("address[" + walletChild.getAddress() + "] initialize try is too many times");
								throw new RuntimeException(); // TODO throw Biz exception
							}
							walletChild.reinitialize();
						}
					} else {
						initialized++;
						if (CollectionUtils.isNotEmpty(walletChild.getHistories())) {
							if (current_index > last_not_empty_index)
								last_not_empty_index = current_index;
						}
					}
				}
				if (initialized == batchChilds.size()) {
					break;
				} else {
					current_index = current_index - batchChilds.size();
				}
			}
			
			//childs.addAll(batchChilds);
			if (current_index - last_not_empty_index >= lookAhead) {
				InitializedPathSummary summary = new InitializedPathSummary(current_index, last_not_empty_index);
				return summary;
			}
			start_index = start_index + step_length;
			step_length = lookAhead - (current_index - last_not_empty_index);
		}
	}
	
	protected List<WalletChild> batchChildInitialize(WalletChildTypes type, List<ChildNumber> path, int start, int length, Map<DeterministicKey, WalletChild> childs) {
		List<WalletChild> batchChilds = new ArrayList<WalletChild>();
		for (int i = start; i < start + length; i++) {
			DeterministicKey key = masterKeyHierarchy.deriveChild(path, true, true, new ChildNumber(i, false));
			WalletChild walletChild = childs.get(key);
			if (walletChild == null) {
				walletChild = new WalletChild(this, key, type, obelisk, blockHeaderService, executor, 9000);
				childs.put(key, walletChild);
			}
			if (!walletChild.isInitialized())
				walletChild.initialize();
			batchChilds.add(walletChild);
		}
		return batchChilds;
	}

	public Map<Future<Object>, WalletChild> renew() {
		List<WalletChild> childs = new ArrayList<WalletChild>();
		childs.addAll(externalChilds.values());
		childs.addAll(internalChilds.values());
		
		Map<Future<Object>, WalletChild> futures = new HashMap<Future<Object>, WalletChild>();
		for (WalletChild child : childs) {
			Future<Object> future = null;
			try {
				future = child.renew(false);
			} catch (Exception e) {
				// socket not found exception
				LOG.error(e.toString());
			}
			if (future != null)
				futures.put(future, child);
		}
		return futures;
	}
	
	public void destroy() {
		if (!connections.isEmpty()) 
			throw new RuntimeException("connections is not empty");
		List<WalletChild> childs = new ArrayList<WalletChild>();
		childs.addAll(externalChilds.values());
		childs.addAll(internalChilds.values());
		for (WalletChild child : childs) {
			child.destroy();
		}
	}
	
	public void addConnection(final WalletConnection connection) {
		connections.add(connection);
		Futures.addCallback(futureInitialized, new FutureCallback<Object>() {
			public void onSuccess(Object result) {
				// initialized event
				WalletInitializedEvent event = new WalletInitializedEvent();
				event.setAccount(WalletAccount.this);
				
				List<UTXO> confirmedSnapshoot = new ArrayList<UTXO>();
				List<UTXO> receivingSnapshoot = new ArrayList<UTXO>();
				List<UTXO> changeSnapshoot = new ArrayList<UTXO>();
				WalletAccount.this.snapshootUTXOs(confirmedSnapshoot, receivingSnapshoot, changeSnapshoot);
				event.setConfirmedUtxos(confirmedSnapshoot);
				event.setReceivingUtxos(receivingSnapshoot);
				event.setChangeUtxos(changeSnapshoot);
				connection.onEvent(event);
			}
			public void onFailure(Throwable thrown) {
			}
		}, executor);
	}

	public void removeConnection(String clientId) {
		assert clientId != null;
		Iterator<WalletConnection> iterator = connections.iterator();
		while (iterator.hasNext()) {
			WalletConnection connection = iterator.next();
			if (clientId.equals(connection.getClientId())) {
				iterator.remove();
				break;
			}
		}
	}

	public Set<WalletConnection> getConnections() {
		return connections;
	}
	
	class InitializedPathSummary {
		private int currentIndex;
		private int lastNotEmptyIndex;

		public InitializedPathSummary(int currentIndex, int lastNotEmptyIndex){
			this.currentIndex = currentIndex;
			this.lastNotEmptyIndex = lastNotEmptyIndex;
		}
		
		public int getCurrentIndex() {
			return currentIndex;
		}
		
		public int getLastNotEmptyIndex() {
			return lastNotEmptyIndex;
		}
	}
	
	public DeterministicKey getMasterKey() {
		return masterKey;
	}

	public String getAfter() {
		return after;
	}

	public int getLookAhead() {
		return lookAhead;
	}

	public long getFirstIndex() {
		return firstIndex;
	}

	public WalletAccoutStatus getStatus() {
		try {
			return (WalletAccoutStatus) BeanUtils.cloneBean(status);
		} catch (IllegalAccessException | InstantiationException | InvocationTargetException | NoSuchMethodException e) {
			LOG.error(e.toString());
			throw new RuntimeException(e);
		}
	}

	public Map<WalletChild, List<UTXO>> getConfirmedUtxos() {
		return confirmedUtxos;
	}

	public Map<WalletChild, List<UTXO>> getReceivingUtxos() {
		return receivingUtxos;
	}

	public Map<WalletChild, List<UTXO>> getChangeUtxos() {
		return changeUtxos;
	}

	public String getMasterKeySerialization() {
		return masterKeySerialization;
	}

	public int getInitializeStatus() {
		return initializeStatus.get();
	}

}
