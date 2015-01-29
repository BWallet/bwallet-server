package com.bdx.bwallet.server.core;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bdx.obelisk.client.Client;
import com.bdx.obelisk.domain.BlockHeader;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;

public class BlockHeaderService {
	
	final static Logger LOG = LoggerFactory.getLogger(BlockHeaderService.class);
	
	private Client obelisk;
	
	private ThreadPoolExecutor executor = new ThreadPoolExecutor(10, 100, 1000L * 60, TimeUnit.MILLISECONDS,
			new LinkedBlockingQueue<Runnable>());;;
	
	private Map<Long, BlockHeader> headers = new ConcurrentHashMap<Long, BlockHeader>();
	
	private Map<Long, Future<Object>> futures = new ConcurrentHashMap<Long, Future<Object>>();
	
	private Lock lock = new ReentrantLock();
	
	public void preprocess(final long height) {
		lock.lock();
		try {
			if (headers.get(height) == null && futures.get(height) == null) {
				if (LOG.isDebugEnabled())
					LOG.debug("try to fetch block header[" + height + "]");
				
				SettableFuture<Object> future = (SettableFuture<Object>)obelisk.getBlockchain().fetchBlockHeader(height);
				futures.put(height, future);
				Futures.addCallback(future, new FutureCallback<Object>() {
					public void onSuccess(Object result) {
						BlockHeaderService.this.onFutureSuccess(height, (BlockHeader)result);
					}
					public void onFailure(Throwable thrown) {
						LOG.error(thrown.toString());
						if (LOG.isDebugEnabled())
							LOG.debug("block header[" + height + "] fetch error or cancel");
					}
				}, executor);
			}
		} finally {
			lock.unlock();
		}
	}
	
	protected void onFutureSuccess(long height, BlockHeader header){
		if (LOG.isDebugEnabled())
			LOG.debug("block header[" + height + "] fetch succeed");
		
		lock.lock();
		try {
			futures.remove(height);
			headers.put(height, header);
		} finally {
			lock.unlock();
		}
	}
	
	protected void cacelAndRemoveFuture(long height) {
		lock.lock();
		try {
			if (futures.get(height).cancel(true)) {
				futures.remove(height);
			}
		} finally {
			lock.unlock();
		}
	}
	
	public BlockHeader getBlockHeader(long height){
		BlockHeader header = headers.get(height);
		int count = 0;
		while (header == null) {
			count++;
			if (count > 40) {
				LOG.error("block header[" + height + "] fetch try is too many times");
				throw new RuntimeException("");	// TODO add exception message
			}
			this.preprocess(height);
			
			Future<Object> future = futures.get(height);
			
			// this future maybe canceled because we don't add lock here
			if (future != null) {
				try {
					header = (BlockHeader)future.get(5000, TimeUnit.MILLISECONDS);
				} catch (InterruptedException | ExecutionException | TimeoutException e) {
					LOG.error(e.toString());
					this.cacelAndRemoveFuture(height);
				} catch (CancellationException e) {
					LOG.error(e.toString());
				}
			}
		}
		return header;
	}

	public void setObelisk(Client obelisk) {
		this.obelisk = obelisk;
	}
}
