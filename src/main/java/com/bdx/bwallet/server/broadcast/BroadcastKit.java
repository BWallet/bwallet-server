package com.bdx.bwallet.server.broadcast;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.channels.FileLock;
import java.util.concurrent.TimeoutException;

import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.CheckpointManager;
import org.bitcoinj.core.DownloadListener;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.PeerEventListener;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Utils;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.SPVBlockStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Service;
import com.subgraph.orchid.TorClient;

public class BroadcastKit extends AbstractIdleService {
	protected static final Logger log = LoggerFactory.getLogger(BroadcastKit.class);

    protected final String filePrefix;
    protected final NetworkParameters params;
    protected volatile BlockChain vChain;
    protected volatile SPVBlockStore vStore;
    protected volatile PeerGroup vPeerGroup;

    protected final File directory;

    protected PeerAddress[] peerAddresses;
    protected PeerEventListener downloadListener;
    protected boolean autoStop = true;
    protected InputStream checkpoints;
    protected boolean blockingStartup = true;
    protected boolean useTor = false;   // Perhaps in future we can change this to true.
    protected String userAgent, version;

    public BroadcastKit(NetworkParameters params, File directory, String filePrefix) {
        this.params = checkNotNull(params);
        this.directory = checkNotNull(directory);
        this.filePrefix = checkNotNull(filePrefix);
        if (!Utils.isAndroidRuntime()) {
            InputStream stream = BroadcastKit.class.getResourceAsStream("/" + params.getId() + ".checkpoints");
            if (stream != null)
                setCheckpoints(stream);
        }
    }

    /** Will only connect to the given addresses. Cannot be called after startup. */
    public BroadcastKit setPeerNodes(PeerAddress... addresses) {
        checkState(state() == State.NEW, "Cannot call after startup");
        this.peerAddresses = addresses;
        return this;
    }

    /** Will only connect to localhost. Cannot be called after startup. */
    public BroadcastKit connectToLocalHost() {
        try {
            final InetAddress localHost = InetAddress.getLocalHost();
            return setPeerNodes(new PeerAddress(localHost, params.getPort()));
        } catch (UnknownHostException e) {
            // Borked machine with no loopback adapter configured properly.
            throw new RuntimeException(e);
        }
    }

    /**
     * If you want to learn about the sync process, you can provide a listener here. For instance, a
     * {@link DownloadListener} is a good choice.
     */
    public BroadcastKit setDownloadListener(PeerEventListener listener) {
        this.downloadListener = listener;
        return this;
    }

    /** If true, will register a shutdown hook to stop the library. Defaults to true. */
    public BroadcastKit setAutoStop(boolean autoStop) {
        this.autoStop = autoStop;
        return this;
    }

    /**
     * If set, the file is expected to contain a checkpoints file calculated with BuildCheckpoints. It makes initial
     * block sync faster for new users - please refer to the documentation on the bitcoinj website for further details.
     */
    public BroadcastKit setCheckpoints(InputStream checkpoints) {
        if (this.checkpoints != null)
            Utils.closeUnchecked(this.checkpoints);
        this.checkpoints = checkNotNull(checkpoints);
        return this;
    }

    /**
     * If true (the default) then the startup of this service won't be considered complete until the network has been
     * brought up, peer connections established and the block chain synchronised. Therefore {@link #startAndWait()} can
     * potentially take a very long time. If false, then startup is considered complete once the network activity
     * begins and peer connections/block chain sync will continue in the background.
     */
    public BroadcastKit setBlockingStartup(boolean blockingStartup) {
        this.blockingStartup = blockingStartup;
        return this;
    }

    /**
     * Sets the string that will appear in the subver field of the version message.
     * @param userAgent A short string that should be the name of your app, e.g. "My Wallet"
     * @param version A short string that contains the version number, e.g. "1.0-BETA"
     */
    public BroadcastKit setUserAgent(String userAgent, String version) {
        this.userAgent = checkNotNull(userAgent);
        this.version = checkNotNull(version);
        return this;
    }

    /**
     * If called, then an embedded Tor client library will be used to connect to the P2P network. The user does not need
     * any additional software for this: it's all pure Java. As of April 2014 <b>this mode is experimental</b>.
     */
    public BroadcastKit useTor() {
        this.useTor = true;
        return this;
    }

    /**
     * This method is invoked on a background thread after all objects are initialised, but before the peer group
     * or block chain download is started. You can tweak the objects configuration here.
     */
    protected void onSetupCompleted() { }

    /**
     * Tests to see if the spvchain file has an operating system file lock on it. Useful for checking if your app
     * is already running. If another copy of your app is running and you start the appkit anyway, an exception will
     * be thrown during the startup process. Returns false if the chain file does not exist.
     */
    public boolean isChainFileLocked() throws IOException {
        RandomAccessFile file2 = null;
        try {
            File file = new File(directory, filePrefix + ".spvchain");
            if (!file.exists())
                return false;
            file2 = new RandomAccessFile(file, "rw");
            FileLock lock = file2.getChannel().tryLock();
            if (lock == null)
                return true;
            lock.release();
            return false;
        } finally {
            if (file2 != null)
                file2.close();
        }
    }

    @Override
    protected void startUp() throws Exception {
        // Runs in a separate thread.
        if (!directory.exists()) {
            if (!directory.mkdirs()) {
                throw new IOException("Could not create directory " + directory.getAbsolutePath());
            }
        }
        log.info("Starting up with directory = {}", directory);
        try {
            File chainFile = new File(directory, filePrefix + ".spvchain");
            boolean chainFileExists = chainFile.exists();

            // Initiate Bitcoin network objects (block store, blockchain and peer group)
            vStore = new SPVBlockStore(params, chainFile);
            if (!chainFileExists && checkpoints != null) {
                // Initialize the chain file with a checkpoint to speed up first-run sync.
                CheckpointManager.checkpoint(params, checkpoints, vStore, Long.MAX_VALUE);
            }
            vChain = new BlockChain(params, vStore);
            vPeerGroup = createPeerGroup();
            vPeerGroup.setMaxConnections(8);
            vPeerGroup.setUseLocalhostPeerWhenPossible(false);
            if (this.userAgent != null)
                vPeerGroup.setUserAgent(userAgent, version);

            // Set up peer addresses or discovery first, so if wallet extensions try to broadcast a transaction
            // before we're actually connected the broadcast waits for an appropriate number of connections.
            if (peerAddresses != null) {
                for (PeerAddress addr : peerAddresses) vPeerGroup.addAddress(addr);
                vPeerGroup.setMaxConnections(peerAddresses.length);
                peerAddresses = null;
            } else {
                vPeerGroup.addPeerDiscovery(new DnsDiscovery(params));
            }
            onSetupCompleted();

            if (blockingStartup) {
                vPeerGroup.startAsync();
                vPeerGroup.awaitRunning();
                // Make sure we shut down cleanly.
                installShutdownHook();

                // TODO: Be able to use the provided download listener when doing a blocking startup.
                final DownloadListener listener = new DownloadListener();
                vPeerGroup.startBlockChainDownload(listener);
                listener.await();
            } else {
                vPeerGroup.startAsync();
                vPeerGroup.addListener(new Service.Listener() {
                    @Override
                    public void running() {
                        final PeerEventListener l = downloadListener == null ? new DownloadListener() : downloadListener;
                        vPeerGroup.startBlockChainDownload(l);
                    }

                    @Override
                    public void failed(State from, Throwable failure) {
                        throw new RuntimeException(failure);
                    }
                }, MoreExecutors.sameThreadExecutor());
            }
        } catch (BlockStoreException e) {
            throw new IOException(e);
        }
    }

    protected PeerGroup createPeerGroup() throws TimeoutException {
        if (useTor) {
            TorClient torClient = new TorClient();
            torClient.getConfig().setDataDirectory(directory);
            return PeerGroup.newWithTor(params, vChain, torClient);
        }
        else
            return new PeerGroup(params, vChain);
    }

    private void installShutdownHook() {
        if (autoStop) Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override public void run() {
                try {
                    BroadcastKit.this.stopAsync();
                    BroadcastKit.this.awaitTerminated();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    @Override
    protected void shutDown() throws Exception {
        // Runs in a separate thread.
        try {
            vPeerGroup.stopAsync();
            vPeerGroup.awaitTerminated();
            vStore.close();

            vPeerGroup = null;
            vStore = null;
            vChain = null;
        } catch (BlockStoreException e) {
            throw new IOException(e);
        }
    }

    public NetworkParameters params() {
        return params;
    }

    public BlockChain chain() {
        checkState(state() == State.STARTING || state() == State.RUNNING, "Cannot call until startup is complete");
        return vChain;
    }

    public SPVBlockStore store() {
        checkState(state() == State.STARTING || state() == State.RUNNING, "Cannot call until startup is complete");
        return vStore;
    }

    public PeerGroup peerGroup() {
        checkState(state() == State.STARTING || state() == State.RUNNING, "Cannot call until startup is complete");
        return vPeerGroup;
    }

    public File directory() {
        return directory;
    }
}
