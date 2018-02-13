/*
 *  Helper class to obtain a Bluetooth RFCOMM connection.
 *
 *  Initially intended specifically for Spire SPm20.
 */

package com.poplatek.pt.android.bluetooth;

import java.util.UUID;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Future;
//import java.util.concurrent.CompletableFuture;  // API level 24 (Nougat)
import java.util.concurrent.FutureTask;
import java.util.concurrent.Callable;

import android.util.Log;
import android.os.ParcelUuid;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothSocket;

import com.poplatek.pt.android.util.CompletableFutureSubset;

public class BluetoothConnect {
    private static final String logTag = "BluetoothConnect";
    private static final String BASE_UUID = "00000000-0000-1000-8000-00805F9B34FB";
    private static final String SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB";
    private BluetoothAdapter btAdapter = null;
    private FutureTask<BluetoothSocket> currTask = null;

    public BluetoothConnect() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Log.w(logTag, "device doesn't support Bluetooth");
            throw new RuntimeException("failed to BluetoothAdapter.getDefaultAdapter(), device doesn't support Bluetooth");
        }
        btAdapter = adapter;
    }

    public Future<BluetoothSocket> connect(String bluetoothMac) {
        // Android Bluetooth seems very unreliable if multiple active connection
        // attempts happen at the same time so prevent it.
        if (currTask != null && !currTask.isDone()) {
            CompletableFutureSubset<BluetoothSocket> fut = new CompletableFutureSubset<BluetoothSocket>();
            fut.completeExceptionally(new IllegalStateException("attempt to connect bluetooth while previous connection active"));
            return fut;
        }

        final String finalBluetoothMac = bluetoothMac;
        FutureTask<BluetoothSocket> task = new FutureTask<BluetoothSocket>(new Callable<BluetoothSocket>() {
            public BluetoothSocket call() throws Exception {
                if (finalBluetoothMac == null) {
                    throw new IllegalArgumentException("null bluetoothMac");
                }
                Log.i(logTag, String.format("connecting bluetooth rfcomm for mac %s", finalBluetoothMac));

                if (btAdapter.isDiscovering()) {
                    Log.w(logTag, "adapter is in discovery mode, this may make RFCOMM connections unreliable");
                }
                if (!btAdapter.isEnabled()) {
                    Log.w(logTag, "bluetooth is disabled, enable it forcibly");
                    boolean ret = btAdapter.enable();
                    Log.i(logTag, String.format("enable() -> %b", ret));
                }

                Log.d(logTag, String.format("get device for mac %s", finalBluetoothMac));
                BluetoothDevice btDevice = btAdapter.getRemoteDevice(finalBluetoothMac);
                Log.d(logTag, "got device");

                // For SPm20:
                // I/BluetoothConnect: UUID 0: 00001101-0000-1000-8000-00805f9b34fb
                // I/BluetoothConnect: UUID 1: 00000000-0000-1000-8000-00805f9b34fb
                // I/BluetoothConnect: UUID 2: 00000000-0000-1000-8000-00805f9b34fb
                // I/BluetoothConnect: UUID 3: ffcacade-afde-cade-defa-cade00000000

                String devAddress = btDevice.getAddress();
                BluetoothClass devClass = btDevice.getBluetoothClass();
                int devBondState = btDevice.getBondState();
                String devName = btDevice.getName();
                //int devType = btDevice.getType();  // Avoid, requires API level 18.
                Log.i(logTag, String.format("got device: address=%s class=%d bondState=%d name=%s",
                                            devAddress, devClass.getDeviceClass(), devBondState, devName));

                ParcelUuid uuids[] = btDevice.getUuids();
                if (uuids == null) {
                    throw new RuntimeException(String.format("no UUIDs found for device %s", devAddress));
                }
                Log.d(logTag, String.format("found %d UUIDs for device %s", uuids.length, devAddress));
                UUID wantUuid = UUID.fromString(SPP_UUID);
                boolean foundUuid = false;
                for (int i = 0; i < uuids.length; i++) {
                    Log.i(logTag, String.format("  UUID %d: %s", i, uuids[i].toString()));
                    if (!foundUuid && uuids[i].getUuid().compareTo(wantUuid) == 0) {
                        foundUuid = true;
                    }
                }
                if (!foundUuid) {
                    throw new RuntimeException(String.format("could not find RFCOMM UUID %s", wantUuid.toString()));
                }

                // Calling connect() may cause the following log:
                //   W/BluetoothAdapter: getBluetoothService() called with no BluetoothManagerCallback
                //
                // This should be harmless.  Note that it is critical that only one
                // connection attempt is made at a time (to the target device); otherwise
                // all of the concurrent attempts may consistently fail.

                Log.i(logTag, String.format("connect to rfcomm using UUID %s", wantUuid.toString()));
                //BluetoothSocket btSocket = btDevice.createRfcommSocketToServiceRecord(wantUuid);
                BluetoothSocket btSocket = btDevice.createInsecureRfcommSocketToServiceRecord(wantUuid);
                //Log.i(logTag, String.format("BluetoothSocket connection type: %d", btSocket.getConnectionType()));  // Avoid, requires API level 23.
                Log.i(logTag, String.format("isConnected: %b", btSocket.isConnected()));
                btSocket.connect();
                Log.i(logTag, String.format("connect returned, isConnected: %b", btSocket.isConnected()));

                // .connect() is synchronous so this should not happen.
                if (!btSocket.isConnected()) {
                    throw new RuntimeException("internal error, expected bluetooth socket to be connected");
                }

                InputStream btIs = btSocket.getInputStream();
                OutputStream btOs = btSocket.getOutputStream();
                if (btIs == null || btOs == null) {
                    throw new RuntimeException("internal error, bluetooth input or output stream is null");
                }

                Log.i(logTag, String.format("successful bluetooth connection to %s, service UUID %s", devAddress, wantUuid));
                return btSocket;
            }
        });
        currTask = task;
        task.run();
        return task;
    }
}
