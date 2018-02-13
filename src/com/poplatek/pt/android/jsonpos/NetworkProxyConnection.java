/*
 *  Handle one TCP connection for NetworkProxy.
 */

package com.poplatek.pt.android.jsonpos;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;

import android.util.Log;
import android.os.SystemClock;

import com.poplatek.pt.android.util.CompletableFutureSubset;

/*package*/ class NetworkProxyConnection {
    private String logTag = "NetworkProxyConnection";
    private static final int CONNECT_TIMEOUT = 10 * 1000;
    private static final int READ_BUFFER_SIZE = 512;
    private static final long READ_THROTTLE_SLEEP = 2 * 1000;

    private String host = null;
    private int port = 0;
    private long id = -1;
    private NetworkProxy proxy = null;
    private long linkSpeed = 0;  // bytes/second
    private Thread readThread = null;
    private Socket sock = null;
    private InputStream sockIs = null;
    private OutputStream sockOs = null;
    LinkedBlockingQueue<byte []> outputQueue = null;
    private CompletableFutureSubset<Void> connectedFuture = new CompletableFutureSubset<Void>();
    private CompletableFutureSubset<Exception> closedFuture = new CompletableFutureSubset<Exception>();
    private ConcurrentLinkedQueue<byte []> readQueue = new ConcurrentLinkedQueue<byte []>();

    public NetworkProxyConnection(String host, int port, long id, NetworkProxy proxy, long linkSpeed) {
        this.host = host;
        this.port = port;
        this.id = id;
        this.proxy = proxy;
        this.linkSpeed = linkSpeed;
        this.logTag = String.format("NetworkProxyConnection(%s:%d/%d)", host, port, id);
    }

    public long getConnectionId() {
        return id;
    }

    public Future<Void> getConnectedFuture() {
        return connectedFuture;
    }

    public Future<Exception> getClosedFuture() {
        return closedFuture;
    }

    public boolean isClosed() {
        return closedFuture.isDone();
    }

    public void start() {
        startReadThread();
    }

    public void close(Exception reason) {
        // For a clean close, the input stream has been read until EOF and all
        // data has been queued to the JsonRpcConnection write queue as Data
        // notifies.  NetworkDisconnected is sent out by NetworkProxy based on
        // the connection having no queued data and the connection being closed.
        // (This relies on queued Data and NetworkDisconnected not being
        // reordered before being written out, which is true now when there's
        // no queue prioritization.)

        reason = (reason != null ? reason : new RuntimeException("closed without reason"));
        connectedFuture.complete(null);
        closedFuture.complete(reason);
        try {
            sock.getInputStream().close();
        } catch (Exception e) {
            Log.d(logTag, "failed to close InputStream", e);
        }
        try {
            sock.getOutputStream().close();
        } catch (Exception e) {
            Log.d(logTag, "failed to close OutputStream", e);
        }

        // At this point closedFuture is set and the connection is finished.
        // There may still be undelivered data, which is delivered by network
        // proxy.  Once the data is delivered, the proxy notifies there's no
        // data and the connection is closed, and issues a NetworkDisconnected.
        try {
            proxy.triggerWriteCheck();
        } catch (Exception e) {
            Log.d(logTag, "failed to trigger proxy write check", e);
        }
    }

    private void startReadThread() {
        readThread = new Thread(new Runnable() {
            public void run() {
                try {
                    runReadThread();
                    close(new RuntimeException("clean close by remote peer"));
                } catch (Exception e) {
                    Log.i(logTag, "read thread failed", e);
                    close(e);
                }
            }
        });
        readThread.start();
    }

    public void write(byte[] data) throws IOException {
        // We could also throttle data sent towards to internet.
        // This is in practice unnecessary with RFCOMM but maybe
        // necessary later when proxying is used with e.g. Wi-Fi
        // terminals.
        Log.d(logTag, String.format("TERMINAL -> INTERNET: %d bytes of data", data.length));
        sockOs.write(data);
    }

    private void runReadThread() throws Exception {
        Log.i(logTag, String.format("start read thread; connecting to %s:%d", host, port));
        sock = new Socket();
        SocketAddress addr = new InetSocketAddress(host, port);
        int timeout = CONNECT_TIMEOUT;
        try {
            sock.connect(addr, timeout);
            Log.i(logTag, String.format("connected to %s:%d, start read loop and write thread", host, port));
            connectedFuture.complete(null);
        } catch (Exception e) {
            connectedFuture.completeExceptionally(e);
            throw e;
        }
        sockIs = sock.getInputStream();
        sockOs = sock.getOutputStream();

        byte buf[] = new byte[READ_BUFFER_SIZE];
        for (;;) {
            if (closedFuture.isDone()) {
                break;
            }
            if (throttleInternetRead()) {
                Log.d(logTag, "too much queued read data, throttle internet reads");
                SystemClock.sleep(READ_THROTTLE_SLEEP);
                continue;
            }

            int got = sockIs.read(buf);
            if (got < 0) {
                Log.i(logTag, "input stream EOF");
                break;
            }
            if (got > buf.length) {
                throw new RuntimeException(String.format("internal error, unexpected read() result %d", got));
            }
            if (got > 0) {
                // Data towards terminal is always queued because throttling it
                // fairly is critical with RFCOMM connections.  NetworkProxy pulls
                // data from the queue.
                Log.d(logTag, String.format("INTERNET -> TERMINAL: %d bytes of data", got));
                readQueue.add(Arrays.copyOfRange(buf, 0, got));
                proxy.triggerWriteCheck();
            }
        }
    }

    public byte[] getQueuedReadData() {
        byte[] res = readQueue.poll();
        return res;
    }

    private boolean throttleInternetRead() {
        // Throttling internet reads is not critical, we just don't want
        // to keep excessive data waiting for transmission.
        long throttleLimit = (linkSpeed * 2 / 3) * 5;  // 5 seconds of unexpanded data
        long queuedBytes = getReadQueueBytes();
        return queuedBytes >= throttleLimit;
    }

    private long getReadQueueBytes() {
        long res = 0;
        for (byte[] data : readQueue) {
            res += data.length;
        }
        return res;
    }
}
