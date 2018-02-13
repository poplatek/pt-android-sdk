/*
 *  Test class demonstrating a persistent, reconnecting Bluetooth network
 *  proxy, intended for Spire SPm20.
 */

package com.poplatek.pt.android.testnetworkproxy;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Future;

import org.json.JSONObject;

import android.util.Log;
import android.os.SystemClock;
import android.bluetooth.BluetoothSocket;

import com.poplatek.pt.android.bluetooth.BluetoothConnect;
import com.poplatek.pt.android.jsonrpc.JsonRpcConnection;
import com.poplatek.pt.android.jsonrpc.JsonRpcDispatcher;
import com.poplatek.pt.android.jsonpos.NetworkProxy;
import com.poplatek.pt.android.util.TokenBucketRateLimiter;

public class BluetoothNetworkProxyTest {
    private static final String logTag = "NetworkProxyTest";
    private static final long retrySleepMillis = 1 * 1000;
    private static final long RFCOMM_DISCARD_TIME = 2 * 1000;
    private static final long SYNC_TIMEOUT = 5 * 1000;
    public static final String DEV_API_KEY = "e086554a-9a35-4bbf-9f99-a8a7cd7d1dcc";
    BluetoothConnect connecter = null;
    String bluetoothMac = null;

    public BluetoothNetworkProxyTest(String bluetoothMac) {
        if (bluetoothMac == null) {
            throw new IllegalArgumentException("null bluetoothMac");
        }
        this.bluetoothMac = bluetoothMac;
        this.connecter = new BluetoothConnect();
    }

    public void runTestLoop() {
        for (;;) {
            try {
                Future<BluetoothSocket> fut = connecter.connect(bluetoothMac);
                BluetoothSocket btSocket = fut.get();
                runProxy(btSocket);
            } catch (Exception e) {
                Log.i(logTag,"bluetooth connect failed, sleep and retry", e);
                SystemClock.sleep(retrySleepMillis);
            }
        }
    }

    private void runProxy(BluetoothSocket btSocket) throws Exception {
        Log.i(logTag, "launch jsonrpc connection");

        InputStream btIs = btSocket.getInputStream();
        OutputStream btOs = btSocket.getOutputStream();

        // Create a JSONRPC connection for the input/output stream, configure
        // it, and start read/write loops.
        final JsonRpcConnection conn = new JsonRpcConnection(btIs, btOs);
        JsonRpcDispatcher disp = new JsonRpcDispatcher();
        conn.setKeepalive();
        conn.setDiscard(RFCOMM_DISCARD_TIME);
        conn.setSync(SYNC_TIMEOUT);
        conn.setDispatcher(disp);
        conn.start();
        Future<Exception> connFut = conn.getClosedFuture();
        Future<Void> readyFut = conn.getReadyFuture();

        // Wait for _Sync to complete before sending anything.
        Log.i(logTag, "wait for connection to become ready");
        readyFut.get();
        SystemClock.sleep(500);

        // Example Status poller, could be used to e.g. update an UI icon.
        new Thread(new Runnable() {
            public void run() {
                try {
                    for (;;) {
                        if (conn.isClosed()) {
                            break;
                        }
                        try {
                            JSONObject params = new JSONObject();
                            JSONObject status = conn.sendRequestSync("Status", null);
                            Log.i(logTag, "STATUS: " + status.toString());
                        } catch (Exception e) {
                            Log.i(logTag, "Status failed", e);
                        }
                        SystemClock.sleep(5000);
                    }
                } catch (Exception e) {
                    Log.d(logTag, "status poller exiting", e);
                }
            }
        }).start();

        /*
           JSONObject params = new JSONObject();
           params.put("api_key", DEV_API_KEY);
           params.put("amount", 123);
           params.put("currency", "EUR");
           params.put("receipt_id", 123);
           params.put("sequence_id", 321);
           JSONObject res = conn.sendRequestSync("Purchase", params, null);
           Log.i(logTag, String.format("PURCHASE RESULT: %s", res.toString()));
         */

        // Check VersionInfo before enabling network proxy.  VersionInfo
        // can provide useful information for choosing rate limits.
        Future<JSONObject> versionInfoFut = conn.sendRequestAsync("VersionInfo", null);
        JSONObject versionInfo = null;
        try {
            versionInfo = versionInfoFut.get();
            Log.i(logTag, String.format("VersionInfo: %s", versionInfo.toString()));
        } catch (Exception e) {
            Log.w(logTag, "VersionInfo failed, ignoring");
        }

        // Rate limits for SPm20, could be chosen based on VersionInfo
        // and link type.
        long jsonrpcWriteTokenRate = 9000;
        long jsonrpcWriteMaxTokens = jsonrpcWriteTokenRate * 2;
        long dataWriteTokenRate = (long) ((double) jsonrpcWriteTokenRate * 0.6);  // 0.6 expands by base64 to about 80% of link, plus overhead and headroom for other requests
        long dataWriteMaxTokens = dataWriteTokenRate * 1;
        Log.i(logTag, String.format("using jsonrpc transport rate limits: maxTokens=%d, rate=%d", jsonrpcWriteMaxTokens, jsonrpcWriteTokenRate));
        Log.i(logTag, String.format("using Data notify rate limits: maxTokens=%d, rate=%d", dataWriteMaxTokens, dataWriteTokenRate));
        TokenBucketRateLimiter connLimiter = new TokenBucketRateLimiter("jsonrpcWrite", jsonrpcWriteMaxTokens, jsonrpcWriteTokenRate);
        TokenBucketRateLimiter dataLimiter = new TokenBucketRateLimiter("dataWrite", dataWriteMaxTokens, dataWriteTokenRate);

        conn.setWriteRateLimiter(connLimiter);

        // _Sync completed, register Network* methods and start networking.
        Log.i(logTag, "starting network proxy");
        NetworkProxy proxy = new NetworkProxy(conn, dataLimiter, jsonrpcWriteTokenRate /*linkSpeed*/);
        proxy.registerNetworkMethods(disp);
        proxy.startNetworkProxySync();

        // Wait for JSONPOS connection to finish, due to any cause.
        Log.i(logTag, "wait for jsonrpc connection to finish");
        Exception closeReason = connFut.get();
        Log.i(logTag, "jsonrpc connection finished");
    }
}
