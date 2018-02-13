/*
 *  JSONPOS network proxy implementation, provides NetworkConnect etc for
 *  a given JsonRpcConnection.  Connections are automatically cleaned up
 *  when the underlying JsonRpcConnection closes.
 *
 *  The network proxy maintains a connectionId -> NetworkConnection mapping.
 *  The proxy handles connect, disconnected, disconnected notify, and
 *  reasonably fair rate limited data writing.
 *
 *  There's a three-level rate limiting strategy:
 *
 *  1. JsonRpcConnection uses a rate limiter and tries to keep withing link
 *     speed limits.  Due to variability of the link speed, this is not
 *     always successful.
 *
 *  2. NetworkProxy rate limits Data notifys so that the notifys, with
 *     base-64 expansion and other overhead, are within a fraction of the
 *     link speed (e.g. 80%).  This leaves some link capacity available
 *     for keepalives and application methods.
 *
 *  3. NetworkProxy monitors the JsonRpcConnection output queue size in
 *     bytes.  If the estimated transfer time of the already queued messages
 *     is too long (several seconds), the proxy stops writing Data notifys.
 *     This may happen if the link is slower than anticipated, and backing
 *     off allows keepalives and other methods to work reasonably.
 */

package com.poplatek.pt.android.jsonpos;

import java.util.HashMap;
import java.util.concurrent.Future;
//import java.util.concurrent.CompletableFuture;  // API level 24 (Nougat)
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;

import android.os.SystemClock;
import android.util.Log;
import android.util.Base64;

import com.poplatek.pt.android.jsonrpc.JsonRpcConnection;
import com.poplatek.pt.android.jsonrpc.JsonRpcDispatcher;
import com.poplatek.pt.android.jsonrpc.JsonRpcThreadMethodHandler;
import com.poplatek.pt.android.jsonrpc.JsonRpcInlineMethodHandler;
import com.poplatek.pt.android.jsonrpc.JsonRpcMethodExtras;
import com.poplatek.pt.android.util.CompletableFutureSubset;
import com.poplatek.pt.android.util.RateLimiter;

public class NetworkProxy {
    private static final String logTag = "NetworkProxy";
    private static final long WRITE_TRIGGER_TIMEOUT = 5000;
    private static final long WRITE_THROTTLE_SLEEP = 2500;

    private HashMap<Long, NetworkProxyConnection> connections = new HashMap<Long, NetworkProxyConnection>();
    private Long connectionIds[] = null;
    private JsonRpcConnection conn = null;
    private RateLimiter dataWriteLimiter = null;
    private CompletableFutureSubset<Void> writeTriggerFuture = new CompletableFutureSubset<Void>();
    private long linkSpeed = 0;
    private boolean started = false;
    private boolean stopped = false;
    private boolean allowConnections = false;

    public NetworkProxy(JsonRpcConnection conn, RateLimiter dataWriteLimiter, long linkSpeed) {
        this.conn = conn;
        this.dataWriteLimiter = dataWriteLimiter;
        this.linkSpeed = linkSpeed;
        updateConnectionKeySet();
    }

    public void registerNetworkMethods(JsonRpcDispatcher dispatcher) {
        final NetworkProxy finalProxy = this;

        dispatcher.registerMethod("NetworkConnect", new JsonRpcThreadMethodHandler() {
            public JSONObject handle(JSONObject params, JsonRpcMethodExtras extras) throws Exception {
                String host = params.getString("host");
                int port = params.getInt("port");
                long id = params.getLong("connection_id");

                Log.i(logTag, String.format("NetworkConnect: %d -> %s:%d", id, host, port));

                if (!allowConnections) {
                    throw new IllegalStateException("reject connection, connections not allowed by proxy at this point");
                }
                if (connections.containsKey(id)) {
                    throw new IllegalArgumentException(String.format("NetworkConnect for an already active connection id %d", id));
                }

                NetworkProxyConnection c = new NetworkProxyConnection(host, port, id, finalProxy, linkSpeed);
                connections.put(id, c);
                updateConnectionKeySet();
                c.start();
                Future connectedFut = c.getConnectedFuture();
                connectedFut.get();  // block until connected/failed

                // Connection closure is handled by the network proxy write
                // loop.  It detects a closed connection with no queued data
                // and sends the necessary NetworkDisconnected.

                return null;
            }
        });
        dispatcher.registerMethod("NetworkDisconnect", new JsonRpcThreadMethodHandler() {
            public JSONObject handle(JSONObject params, JsonRpcMethodExtras extras) throws Exception {
                long id = params.getLong("connection_id");
                String reason = params.optString("reason");
                Log.i(logTag, String.format("NetworkDisconnect: %d", id));

                NetworkProxyConnection c = connections.get(id);
                if (c == null) {
                    throw new IllegalArgumentException(String.format("NetworkDisconnect for non-existent connection %d", id));
                }
                c.close(reason != null ? new RuntimeException(reason) : new RuntimeException("peer requested closed"));
                Future closedFut = c.getClosedFuture();
                closedFut.get();  // XXX: unclean wrapped exception when ExecutionException

                return null;
            }
        });
        dispatcher.registerMethod("Data", new JsonRpcInlineMethodHandler() {
            // Must be handled efficiently, use inline handler.
            public JSONObject handle(JSONObject params, JsonRpcMethodExtras extras) throws Exception {
                long id = params.getLong("id");
                String data64 = params.getString("data");

                NetworkProxyConnection c = connections.get(id);
                if (c == null) {
                    throw new IllegalStateException(String.format("received Data for non-existent connection id %d", id));
                }

                try {
                    byte[] data = Base64.decode(data64, Base64.DEFAULT);
                    c.write(data);
                } catch (Exception e) {
                    Log.i(logTag, "failed to decode incoming Data, closing tcp connection", e);
                    c.close(e);
                    throw e;
                }

                return null;
            }
        });
    }

    public void startNetworkProxySync() throws Exception {
        if (started) {
            throw new IllegalStateException("already started");
        }

        conn.sendRequestSync("NetworkStart", null, null);
        started = true;
        allowConnections = true;

        Thread writerThread = new Thread(new Runnable() {
            public void run() {
                Exception cause = null;
                try {
                    runWriteLoop();
                } catch (Exception e) {
                    Log.d(logTag, "write loop failed", e);
                    cause = e;
                }

                forceCloseConnections(cause);
                try {
                    stopNetworkProxySync();
                } catch (Exception e) {
                    Log.d(logTag, "failed to close proxy", e);
                }
            }
        });
        writerThread.start();
    }

    public void stopNetworkProxySync() throws Exception {
        if (!started) {
            return;
        }
        if (stopped) {
            return;
        }
        stopped = true;
        allowConnections = false;
        conn.sendRequestSync("NetworkStop", null, null);
        forceCloseConnections(new RuntimeException("proxy stopping"));
    }

    private void forceCloseConnections(Throwable cause) {
        allowConnections = false;
        try {
            // Avoid concurrent modification error by getting a snapshot of
            // the key set (which should no longer grow).
            NetworkProxyConnection conns[] = connections.values().toArray(new NetworkProxyConnection[0]);
            for (NetworkProxyConnection c : conns) {
                Log.i(logTag, "closing proxy connection ID " + c.getConnectionId());
                try {
                    c.close(new RuntimeException("proxy exiting", cause));
                } catch (Exception e) {
                    Log.w(logTag, "failed to close tcp connection", e);
                }
            }
        } catch (Exception e) {
            Log.w(logTag, "failed to close tcp connections", e);
        }
    }

    private void runWriteLoop() throws Exception {
        int idIndex = 0;
        for (;;) {
            //Log.v(logTag, "network proxy write loop");
            if (conn.isClosed()) {
                Log.d(logTag, "network proxy write loop exiting, jsonrpc connection is closed");
                break;
            }
            if (stopped) {
                Log.d(logTag, "network proxy write loop exiting, stopped==true");
                break;
            }

            // If underlying JsonRpcConnection has too much queued data,
            // stop writing for a while because we don't want the queue
            // to become too large.  This is a backstop which tries to
            // ensure that the connection remains minimally responsible
            // even if Data rate limiting doesn't correctly match assumed
            // connection speed.
            if (throttleJsonRpcData()) {
                Log.d(logTag, "connection queue too long, throttle proxy writes");
                SystemClock.sleep(WRITE_THROTTLE_SLEEP);
                continue;
            }

            // Rough fairness; run a looping index over the connection
            // ID set.  The set may change so we may skip or process
            // a certain ID twice, but this happens very rarely in
            // practice so it doesn't matter.
            boolean wrote = false;
            Long[] keys = connectionIds;
            if (keys.length == 0) {
                idIndex = 0;
            } else {
                idIndex = (idIndex + 1) % keys.length;
                int startIndex = idIndex;
                int currIndex = startIndex;
                while (true) {
                    long id = keys[currIndex];
                    NetworkProxyConnection c = connections.get(id);
                    //Log.v(logTag, String.format("check index %d/%d, conn id %d for data", currIndex, keys.length, id));
                    if (c != null) {
                        byte[] data = c.getQueuedReadData();
                        if (data != null) {
                            // Queue data to be written to JSONRPC.  Here we assume the caller is
                            // only providing us with reasonably small chunks (see read buffer size
                            // in NetworkProxyConnection) so that they can be written out as individual
                            // Data notifys without merging or splitting.

                            //Log.v(logTag, String.format("connection id %d has data (chunk is %d bytes)", id, data.length));

                            if (dataWriteLimiter != null) {
                                dataWriteLimiter.consumeSync(data.length);  // unencoded size
                            }
                            JSONObject params = new JSONObject();
                            params.put("id", id);  // connection id
                            params.put("data", Base64.encodeToString(data, Base64.NO_WRAP));
                            conn.sendNotifySync("Data", params);

                            wrote = true;
                            break;
                        } else if (c.isClosed()) {
                            // Connection has no data and is closed, issue NetworkDisconnected
                            // and stop tracking.

                            //Log.v(logTag, String.format("connection id %d has no data and is closed -> send NetworkDisconnected", id));

                            String reason = null;
                            Future<Exception> closedFut = c.getClosedFuture();
                            try {
                                Exception e = closedFut.get();
                                reason = e.toString();
                            } catch (Exception e) {
                                reason = "failed to get reason: " + e.toString();
                            }

                            connections.remove(id);
                            updateConnectionKeySet();

                            JSONObject params = new JSONObject();
                            params.put("connection_id", id);
                            if (reason != null) {
                                params.put("reason", reason);
                            }

                            // Result is ignored for now.  We could maybe retry on error
                            // but there's no known reason for this to fail.
                            conn.sendRequestAsync("NetworkDisconnected", params);

                            wrote = true;
                            break;
                        }
                    }
                    currIndex = (currIndex + 1) % keys.length;
                    if (currIndex == startIndex) {
                        break;
                    }
                }
            }

            // If we didn't write data, wait for a trigger future or
            // sanity timeout.
            if (!wrote) {
                if (writeTriggerFuture.isDone()) {
                    //Log.v(logTag, "refresh network proxy write trigger future");
                    writeTriggerFuture = new CompletableFutureSubset<Void>();
                }
                //Log.v(logTag, "proxy did not write data, wait for trigger");
                try {
                    writeTriggerFuture.get(WRITE_TRIGGER_TIMEOUT, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    /* No trigger, sanity poll. */
                }
            }
        }
    }

    public boolean throttleJsonRpcData() {
        // If underlying JsonRpcConnection write queue is too long (estimated
        // to be several seconds long, making it likely a _Keepalive would
        // fail), throttle writing Data to the JsonRpcConnection.
        long throttleLimit = linkSpeed * 2;  // 2 seconds of data (at estimated link speed)
        long queuedBytes = conn.getWriteQueueBytes();
        return queuedBytes >= throttleLimit;
    }

    private void updateConnectionKeySet() {
        Long keys[] = connections.keySet().toArray(new Long[0]);
        connectionIds = keys;
    }

    public void triggerWriteCheck() {
        writeTriggerFuture.complete(null);
    }
}
