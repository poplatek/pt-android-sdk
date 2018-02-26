/*
 *  Run a persistent JSONPOS network proxy over Bluetooth RFCOMM to a given,
 *  already paired MAC address or automatic detection of available device.
 */

package com.poplatek.pt.android.jsonpos;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Future;

import org.json.JSONObject;

import android.util.Log;
import android.os.SystemClock;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothDevice;

import com.poplatek.pt.android.bluetooth.BluetoothConnect;
import com.poplatek.pt.android.jsonrpc.JsonRpcConnection;
import com.poplatek.pt.android.jsonrpc.JsonRpcDispatcher;
import com.poplatek.pt.android.jsonrpc.JsonRpcInlineMethodHandler;
import com.poplatek.pt.android.jsonrpc.JsonRpcMethodExtras;
import com.poplatek.pt.android.jsonpos.NetworkProxy;
import com.poplatek.pt.android.util.TokenBucketRateLimiter;
import com.poplatek.pt.android.util.ExceptionUtil;
import com.poplatek.pt.android.util.TerminalVersion;

public class BluetoothNetworkProxyRunner {
    public interface DebugStatusCallback {
        void updateStatus(String text) throws Exception;
    }

    private static final String logTag = "BluetoothProxy";
    private static final long RETRY_SLEEP_MILLIS = 1000;
    private static final long RFCOMM_DISCARD_TIME = 2000;
    private static final long SYNC_TIMEOUT = 5000;
    private static final long SPM20_LINK_SPEED = 10 * 1024;  // Default without .link_speed
    private static final long MIN_LINK_SPEED = 10 * 1024;

    BluetoothConnect connecter = null;
    String bluetoothMac = null;
    DebugStatusCallback statusCb = null;

    public BluetoothNetworkProxyRunner(String bluetoothMac) {
        this.bluetoothMac = bluetoothMac;  // null = autodetect
        this.connecter = new BluetoothConnect();
    }

    public void setDebugStatusCallback(DebugStatusCallback cb) {
        statusCb = cb;
    }

    public void updateDebugStatus(String text) {
        try {
            if (statusCb != null) {
                statusCb.updateStatus(text);
            }
        } catch (Exception e) {
            Log.d(logTag, "failed to update debug status, ignoring", e);
        }
    }

    public void runProxyLoop() {
        for (;;) {
            try {
                String mac = bluetoothMac != null ? bluetoothMac : autodetectTargetMac();
                updateDebugStatus("Connecting RFCOMM to " + mac);
                Future<BluetoothSocket> fut = connecter.connect(mac);
                BluetoothSocket btSocket = fut.get();
                updateDebugStatus("Success, start network proxy to " + mac);
                runProxy(btSocket);
            } catch (Exception e) {
                updateDebugStatus("FAILED: " + ExceptionUtil.unwrapExecutionExceptionsToThrowable(e).toString());
                Log.i(logTag, "bluetooth connect failed, sleep and retry", e);
                SystemClock.sleep(RETRY_SLEEP_MILLIS);
            }
        }
    }

    // Automatic target detection based on sorted device list and filtering
    // for Poplatek relevant devices (such as SPm20).  If multiple devices
    // are available, uses the first available, compatible device.  Unpair
    // any undesired devices manually if necessary.
    private String autodetectTargetMac() throws Exception {
        Log.i(logTag, "autodetect target MAC");
        BluetoothDevice[] devs = connecter.getAvailableDevices();
        if (devs.length == 0) {
            throw new Exception("cannot autodetect a terminal");
        }

        Log.i(logTag, "autodetected " + devs[0].getName() + " (" + devs[0].getAddress() + ")");
        return devs[0].getAddress();
    }

    private void handleJsonposStatusUpdate(JSONObject status) {
        Log.i(logTag, "STATUS: " + status.toString());
        updateDebugStatus("Status: " + status.toString());
    }

    // Status poller for older terminals with no StatusEvent support.
    private void runStatusPoller(final JsonRpcConnection conn) throws Exception {
        new Thread(new Runnable() {
            public void run () {
                try {
                    for (;;) {
                        if (conn.isClosed()) {
                            break;
                        }
                        try {
                            JSONObject params = new JSONObject();
                            JSONObject status = conn.sendRequestSync("Status", params);
                            handleJsonposStatusUpdate(status);
                        } catch (Exception e) {
                            Log.i(logTag, "Status failed, ignoring", e);
                        }
                        SystemClock.sleep(5000);
                    }
                } catch (Exception e) {
                    Log.d(logTag, "status poller failed", e);
                }
                Log.i(logTag, "status poller exiting");
            }
        }).start();
    }

    private void runProxy(BluetoothSocket btSocket) throws Exception {
        Log.i(logTag, "launch jsonrpc connection");

        InputStream btIs = btSocket.getInputStream();
        OutputStream btOs = btSocket.getOutputStream();

        // Method dispatcher for connection.
        JsonRpcDispatcher disp = new JsonRpcDispatcher();
        disp.registerMethod("StatusEvent", new JsonRpcInlineMethodHandler() {
            public JSONObject handle(JSONObject params, JsonRpcMethodExtras extras) throws Exception {
                handleJsonposStatusUpdate(params);
                return new JSONObject();
            }
        });

        // Create a JSONRPC connection for the input/output stream, configure
        // it, and start read/write loops.
        final JsonRpcConnection conn = new JsonRpcConnection(btIs, btOs);
        conn.setKeepalive();
        //conn.setDiscard(RFCOMM_DISCARD_TIME);  // unnecessary if _Sync reply scanning is reliable
        conn.setSync(SYNC_TIMEOUT);
        conn.setDispatcher(disp);
        conn.start();
        Future<Exception> connFut = conn.getClosedFuture();
        Future<Void> readyFut = conn.getReadyFuture();

        // Wait for _Sync to complete before sending anything.
        Log.i(logTag, "wait for connection to become ready");
        readyFut.get();

        // Check TerminalInfo before enabling network proxy.  TerminalInfo
        // can provide useful information for choosing rate limits. Fall back
        // to VersionInfo (older terminals).  The 'android_sdk_version' is not
        // a required field, but is given informatively.
        JSONObject terminalInfo = null;
        String[] versionMethods = { "TerminalInfo", "VersionInfo" };
        for (String method : versionMethods) {
            JSONObject terminalInfoParams = new JSONObject();
            terminalInfoParams.put("android_sdk_version", com.poplatek.pt.android.sdk.Sdk.getSdkVersion());
            Future<JSONObject> terminalInfoFut = conn.sendRequestAsync(method, terminalInfoParams);
            try {
                terminalInfo = terminalInfoFut.get();
                Log.i(logTag, String.format("%s: %s", method, terminalInfo.toString()));
                break;
            } catch (Exception e) {
                Log.w(logTag, String.format("%s: failed, ignoring", method), e);
            }
        }
        if (terminalInfo == null) {
            Log.w(logTag, "failed to get TerminalInfo");
            terminalInfo = new JSONObject();
        }

        // Feature detection based on version comparison.  Terminal versions
        // numbers have the format MAJOR.MINOR.PATCH where each component is
        // a number.  Use version comparison helper class for comparisons.
        String terminalVersion = terminalInfo.optString("version", null);
        if (terminalVersion != null) {
            Log.i(logTag, "terminal software version is: " + terminalVersion);
            if (terminalVersion.equals("0.0.0")) {
                Log.w(logTag, "terminal is running an unversioned development build (0.0.0)");
            }
            try {
                if (TerminalVersion.supportsStatusEvent(terminalVersion)) {
                    Log.i(logTag, "terminal supports StatusEvent, no need for polling");
                } else {
                    Log.i(logTag, "terminal does not support StatusEvent, poll using Status request");
                    runStatusPoller(conn);
                }
            } catch (Exception e) {
                Log.w(logTag, "failed to parse terminal version", e);
            }
        } else {
            Log.w(logTag, "terminal software version is unknown");
        }

        // Rate limits are based on the known or estimated base link speed.
        // Use .link_speed from TerminalInfo response (with a sanity minimum)
        // so that SPm20 link speed differences are taken into account
        // automatically.  Assume a hardcoded default if no .link_speed is
        // available.
        long linkSpeed = terminalInfo.optLong("link_speed", SPM20_LINK_SPEED);
        linkSpeed = Math.max(linkSpeed, MIN_LINK_SPEED);
        Log.i(logTag, String.format("use base link speed %d bytes/second", linkSpeed));

        // Compute other rate limits from the base link speed.
        long jsonrpcWriteTokenRate = linkSpeed;
        long jsonrpcWriteMaxTokens = (long) ((double) jsonrpcWriteTokenRate * 0.25);  // ~250ms buffered data maximum
        long dataWriteTokenRate = (long) ((double) jsonrpcWriteTokenRate * 0.4);  // 0.5 expands by base64 to about 70-80% of link, plus overhead and headroom for other requests
        long dataWriteMaxTokens = (long) ((double) jsonrpcWriteTokenRate * 0.4 * 0.25);  // ~250ms buffered data maximum
        Log.i(logTag, String.format("using jsonrpc transport rate limits: maxTokens=%d, rate=%d", jsonrpcWriteMaxTokens, jsonrpcWriteTokenRate));
        Log.i(logTag, String.format("using Data notify rate limits: maxTokens=%d, rate=%d", dataWriteMaxTokens, dataWriteTokenRate));
        TokenBucketRateLimiter connLimiter = new TokenBucketRateLimiter("jsonrpcWrite", jsonrpcWriteMaxTokens, jsonrpcWriteTokenRate);
        TokenBucketRateLimiter dataLimiter = new TokenBucketRateLimiter("dataWrite", dataWriteMaxTokens, dataWriteTokenRate);
        conn.setWriteRateLimiter(connLimiter);

        // _Sync and other handshake steps completed, register Network*
        // methods and start networking.  This could be made faster by
        // starting the network proxy right after _Sync, and then updating
        // the rate limits once we have a TerminalInfo response.
        Log.i(logTag, "starting network proxy");
        NetworkProxy proxy = new NetworkProxy(conn, dataLimiter, jsonrpcWriteTokenRate /*linkSpeed*/);
        proxy.registerNetworkMethods(disp);
        proxy.startNetworkProxySync();

        //com.poplatek.pt.android.tests.JsonposTests.testFileDownloads(conn);
        //com.poplatek.pt.android.tests.JsonposTests.testSuspend(conn);
        //com.poplatek.pt.android.tests.JsonposTests.testTerminalVersionCompare();

        // Wait for JSONPOS connection to finish, due to any cause.
        Log.i(logTag, "wait for jsonrpc connection to finish");
        Exception closeReason = connFut.get();
        Log.i(logTag, "jsonrpc connection finished");
    }
}
