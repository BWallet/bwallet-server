package com.bdx.bwallet.server.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.bitcoinj.crypto.DeterministicKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bdx.bwallet.server.core.event.WalletEvent;
import com.bdx.bwallet.server.core.model.TX;
import com.bdx.bwallet.server.core.model.WalletAccoutStatus;
import com.bdx.obelisk.client.Client;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

public class WalletService {

	final static Logger LOG = LoggerFactory.getLogger(WalletService.class);
	
	final static long DEFAULT_CLEAN_INTERVAL = 1 * 60 * 1000;
	
	private int corePoolSize = 20;
	
	private int maximumPoolSize = 20;
	
	private int initializeQueueSize = 2;
	
	private Map<String, WalletConnection> connections = new ConcurrentHashMap<String, WalletConnection>();

	private Map<DeterministicKey, WalletAccount> accounts = new ConcurrentHashMap<DeterministicKey, WalletAccount>();

	private Client obelisk = null;

	private BlockHeaderService blockHeaderService;
	
	private ThreadPoolExecutor executor;
	
	private Lock lock = new ReentrantLock();
	
	private Thread cleanThread;
	
	private Thread initializeThread;
	
	private long cleanInterval = DEFAULT_CLEAN_INTERVAL;
	
	private BlockingQueue<WalletAccount> initializeQueue = new LinkedBlockingQueue<WalletAccount>(); 
	
	public WalletService() {
	}
	
	public void setup() {
		executor = new ThreadPoolExecutor(corePoolSize, maximumPoolSize, 1000L * 60, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<Runnable>());;
				
		cleanThread = new Thread(new Runnable(){
			public void run() {
				while(!Thread.currentThread().isInterrupted()){
					WalletService.this.cleanDeadAndRenew();
					try {
						Thread.sleep(cleanInterval);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}
		});
		cleanThread.start();
		
		initializeThread = new Thread(new Runnable(){
			public void run() {
				final Set<ListenableFuture<Void>> futures = Collections.synchronizedSet(new HashSet<ListenableFuture<Void>>());
				while(!Thread.currentThread().isInterrupted()){
					try {
						if (futures.size() < initializeQueueSize) {
							//System.out.println("futures.size() " + futures.size());
							WalletAccount account = initializeQueue.poll(1000, TimeUnit.MILLISECONDS);
							if (account != null && account.getInitializeStatus() == -1) {
								final ListenableFuture<Void> future = (ListenableFuture<Void>)account.initialize();
								futures.add(future);
								Futures.addCallback(future, new FutureCallback<Object>() {
									public void onSuccess(Object result) {
										futures.remove(future);
									}
									public void onFailure(Throwable thrown) {
										futures.remove(future);
									}
								});
							}
						}
					} catch (Exception e) {
						LOG.error(e.toString());
					}
				}
			}
		});
		initializeThread.start();
	}
	
	public String createConnection() {
		String clientId = UUID.randomUUID().toString();
		WalletConnection connection = new WalletConnection(clientId);
		connections.put(clientId, connection);
		return clientId;
	}

	public WalletAccoutStatus[] poll(String clientId, long timeout, TimeUnit unit) {
		WalletConnection connection = connections.get(clientId);
		List<WalletAccoutStatus> statusList = new ArrayList<WalletAccoutStatus>();
		// TODO throw exception if connection is null
		if (connection != null) {
			if (connection.getAccounts().size() > 0) {
				for (WalletAccount account : connection.getAccounts()) {
					// reinitialize the account that failed to subscribe in the polling
					if (account.getInitializeStatus() == -1) {
						// account.initialize();
						try {
							initializeQueue.put(account);
						} catch (InterruptedException e) {
							LOG.error(e.toString());
						}
					}
				}
			}
			WalletEvent event = connection.pollEvent(timeout, unit);
			if (event != null) {
				// merge events by account
				Map<WalletAccount, WalletAccoutStatus> statusMap = new HashMap<WalletAccount, WalletAccoutStatus>();
				WalletAccoutStatus status = eventToStatus(event);
				statusMap.put(event.getAccount(), status);
				while(true) {
					event = connection.pollEvent();
					if (event == null)
						break;
					else {
						status = eventToStatus(event);
						statusMap.put(event.getAccount(), status);
					}
				}
				statusList.addAll(statusMap.values());
			}
		}
		return statusList.toArray(new WalletAccoutStatus[]{});
	}
	
	protected WalletAccoutStatus eventToStatus(WalletEvent event) {
		if (event == null)
			return null;
		WalletAccoutStatus status = event.getAccount().getStatus();
		status.setConfirmed(event.getConfirmedUtxos());
		status.setReceiving(event.getReceivingUtxos());
		status.setChange(event.getChangeUtxos());
		return status;
	}
	
	public WalletAccoutStatus subscribe(String clientId, String publicMaster, String after, int lookAhead, long firstIndex) {
		assert obelisk != null;
		assert publicMaster != null;
		
		if (LOG.isDebugEnabled())
			LOG.debug("subscribe account[" + publicMaster + "] from client[" + clientId + "]");
		
		WalletConnection connection = connections.get(clientId);
		if (connection != null) {
			DeterministicKey masterKey = DeterministicKey.deserializeB58(null, publicMaster);
			
			WalletAccount account = null;
			
			// atomic block for one masterKey
			lock.lock();
			try {
				account = accounts.get(masterKey);
				if (account == null){
					account = new WalletAccount(masterKey, publicMaster, obelisk, blockHeaderService, executor, after, lookAhead, firstIndex);
					//account.initialize();
					
					try {
						initializeQueue.put(account);
					} catch (InterruptedException e) {
						LOG.error(e.toString());
					}
					
					accounts.put(masterKey, account);
				}
			} finally {
				lock.unlock();
			}
			
			// use case : user refresh page when failed to subscribe 
			if (account.getInitializeStatus() == -1) {
				// account.initialize();
				try {
					initializeQueue.put(account);
				} catch (InterruptedException e) {
					LOG.error(e.toString());
				}
			}
			
			connection.addAccount(account);
			account.addConnection(connection);
			
			WalletEvent event = connection.pollEvent(account);
			WalletAccoutStatus status = account.getStatus();
			if (event != null) {
				status.setConfirmed(event.getConfirmedUtxos());
				status.setReceiving(event.getReceivingUtxos());
				status.setChange(event.getChangeUtxos());
			} else {
				// account maybe initialized but event received by the poll method, so we tell client to ignore this response.
				status.setStatus("PENDING");
			}
			
			return status;
		} else {
			// TODO throw exception
			return null;
		}
	}

	public List<TX> getTXs(String publicMaster){
		DeterministicKey masterKey = DeterministicKey.deserializeB58(null, publicMaster);
		WalletAccount account = accounts.get(masterKey);
		return account.getTXs();
	}
	
	public TX getTX(String publicMaster, String hash){
		DeterministicKey masterKey = DeterministicKey.deserializeB58(null, publicMaster);
		WalletAccount account = accounts.get(masterKey);
		if (account != null)
			return account.getTX(hash);
		else
			return null;
	}
	
	public void send(byte[] tx) {
		Future<Object> future = obelisk.getProtocol().broadcastTransaction(tx);
		int retry = 0;
		while (true) {
			try {
				future.get(3000, TimeUnit.MILLISECONDS);
				return ;
			} catch (InterruptedException | TimeoutException e) {
				LOG.error(e.toString());
				
				if (retry > 5)
					throw new RuntimeException(e.getMessage());
				
				future = obelisk.getProtocol().broadcastTransaction(tx);
				retry++;
			} catch (ExecutionException e) {
				LOG.error(e.toString());
				throw new RuntimeException(e.getCause().getMessage());	
			}
		}
	}
	
	protected void cleanDeadAndRenew() {
		// clear the dead connection and account
		for (WalletConnection conn : connections.values()) {
			if (conn.isDead()) {
				connections.remove(conn.getClientId());
				if (conn.getAccounts().size() > 0) {
					for (WalletAccount account : conn.getAccounts()) {
						account.removeConnection(conn.getClientId());
						if (account.getConnections().isEmpty()) {
							lock.lock();
							try {
								accounts.remove(account.getMasterKey());
								account.destroy();
							} catch (Exception e) {
								LOG.error(e.toString());
							} finally {
								lock.unlock();
							}
						}
					}
					conn.getAccounts().clear();
				}
			}
		}
		
		// renew accounts
		Collection<WalletAccount> walletAccounts = accounts.values();
		Map<Future<Object>, WalletChild> allAccountFutures = new HashMap<Future<Object>, WalletChild>();
		for (WalletAccount account : walletAccounts) {
			Map<Future<Object>, WalletChild> accountFutures = account.renew();
			allAccountFutures.putAll(accountFutures);
		}
		Map<WalletChild, Integer> retries = new HashMap<WalletChild, Integer>();
		Map<WalletChild, Integer> regets = new HashMap<WalletChild, Integer>();
		while(true) {
			allAccountFutures = this.retryRenew(allAccountFutures, retries, regets);
			if (allAccountFutures.isEmpty()) {
				break;
			}
		}
	}
	
	protected Map<Future<Object>, WalletChild> retryRenew(Map<Future<Object>, WalletChild> allAccountFutures,
			Map<WalletChild, Integer> retries, Map<WalletChild, Integer> regets) {
		// get renew result, retry if timeout or error
		Set<Future<Object>> futures = allAccountFutures.keySet();
		Map<Future<Object>, WalletChild> retryFutures = new HashMap<Future<Object>, WalletChild>();
		for (Future<Object> future : futures) {
			try {
				future.get(800, TimeUnit.MILLISECONDS);
				break;
			} catch (InterruptedException | ExecutionException | TimeoutException e) {
				LOG.error(e.getMessage());
				
				WalletChild child = allAccountFutures.get(future);
				Integer reget = regets.get(child);
				if (reget == null) {
					reget = 0;
				}
				if (reget < 21) {
					reget = reget + 1;
					retryFutures.put(future, child);
				} else {
					reget = 0;
					if (future.cancel(true)) {
						Integer retry = retries.get(child);
						if (retry == null) {
							retry = 0;
						}
						if (retry < 21) {
							retry = retry + 1;
							retries.put(child, retry);
							try {
								Future<Object> retyFuture = child.renew(false);
								retryFutures.put(retyFuture, child);
							} catch (Exception ex) {
								// socket not found error
								LOG.error(ex.toString());
							}
						} else {
							// TODO re-subscribe?
							LOG.error(child.getAddress() + " retry more than 20 to renew");
						}
					}
				}
				regets.put(child, reget);
			}
		}
		return retryFutures;
	}

	public void destroy() {
		cleanThread.interrupt();
		try {
			cleanThread.join();
		} catch (InterruptedException e) {
			LOG.error(e.getMessage());
		}
		
		initializeThread.interrupt();
		try {
			initializeThread.join();
		} catch (InterruptedException e) {
			LOG.error(e.getMessage());
		}
	}
	
	public void setObelisk(Client obelisk) {
		this.obelisk = obelisk;
	}

	public void setBlockHeaderService(BlockHeaderService blockHeaderService) {
		this.blockHeaderService = blockHeaderService;
	}

	public void setCleanInterval(long cleanInterval) {
		this.cleanInterval = cleanInterval;
	}

	public void setCorePoolSize(int corePoolSize) {
		this.corePoolSize = corePoolSize;
	}

	public void setMaximumPoolSize(int maximumPoolSize) {
		this.maximumPoolSize = maximumPoolSize;
	}

	public void setInitializeQueueSize(int initializeQueueSize) {
		this.initializeQueueSize = initializeQueueSize;
	}

}
