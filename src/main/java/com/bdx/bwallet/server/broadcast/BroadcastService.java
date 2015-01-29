package com.bdx.bwallet.server.broadcast;

import java.io.File;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.utils.BriefLogFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.bdx.bwallet.server.core.WalletService;
import com.bdx.bwallet.server.core.model.TX;
import com.google.common.util.concurrent.AbstractIdleService;

@Service("broadcastService")
public class BroadcastService extends AbstractIdleService implements InitializingBean, DisposableBean {

	final static Logger LOG = LoggerFactory.getLogger(BroadcastService.class);
	
	@Value("${broadcast.directory}")
	private String directory;
	
	private BroadcastKit kit;

	@Autowired
	private WalletService walletService;
	
	protected Transaction broadcastTransaction(byte[] tx) {
		Transaction transaction = new Transaction(MainNetParams.get(), tx);
		kit.peerGroup().broadcastTransaction(transaction);
		return transaction;
	}
	
	public void broadcast(byte[] tx, String publicMaster){
		Transaction transaction = this.broadcastTransaction(tx);
		int count = 0;
		while (true) {
			count++;
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			
			if (publicMaster != null) {
				TX t = walletService.getTX(publicMaster, transaction.getHashAsString());
				if (t != null) {
					if (LOG.isDebugEnabled())
						LOG.debug("transaction[" + transaction.getHashAsString() + "] broadcast status found using walletService");
					return;
				}
			}
			
			int num = transaction.getConfidence().numBroadcastPeers();
			if (num > 0) {
				if (LOG.isDebugEnabled())
					LOG.debug("transaction[" + transaction.getHashAsString() + "] broadcast status found using transactionConfidence");
				return;
			}
			
			if (count > 120) {
				LOG.error("broadcast state detection timeout for transaction[" + transaction.getHashAsString() + "]");
				//throw new RuntimeException("broadcast state detection timeout");
			}
		}
	}
	
	@Override
	public void destroy() throws Exception {
		try{
			this.stopAsync();
			this.awaitTerminated();
		} catch (Exception e){
			e.printStackTrace();
		}
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		try{
			this.startAsync();
		} catch (Exception e){
			e.printStackTrace();
		}
	}

	@Override
	protected void startUp() throws Exception {
		// This line makes the log output more compact and easily read,
		// especially when using the JDK log adapter.
		BriefLogFormatter.init();

		// Start up a basic app using a class that automates some boilerplate.
		kit = new BroadcastKit(MainNetParams.get(), new File(directory), "bwallet");

		//kit.setPeerNodes(new PeerAddress(InetAddress.getByName("192.168.8.100")));
		
		// Download the block chain and wait until it's done.
		kit.startAsync();
		kit.awaitRunning();
		
		if (LOG.isInfoEnabled())
			LOG.info("BroadcastService started.");
	}

	@Override
	protected void shutDown() throws Exception {
		kit.stopAsync();
		kit.awaitTerminated();
	}

}
