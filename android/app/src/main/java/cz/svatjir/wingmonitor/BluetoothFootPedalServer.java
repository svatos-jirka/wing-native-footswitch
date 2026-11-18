package cz.svatjir.wingmonitor;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.ParcelUuid;

import java.util.UUID;

/** BLE GATT receiver for a simple three-switch pedal or the PC pedal simulator. */
final class BluetoothFootPedalServer {
    static final int LEFT = 1;
    static final int CENTER = 2;
    static final int RIGHT = 3;

    // Keep these UUIDs and the two-byte payload in sync with tools/pc-simulator/README.md.
    static final UUID SERVICE_UUID = UUID.fromString("8d53dc1d-1db7-4cd3-868b-8a527460aa84");
    static final UUID COMMAND_UUID = UUID.fromString("8d53dc1d-1db7-4cd3-868b-8a527460aa85");

    interface Listener {
        void onStatus(String status, boolean connected);
        void onButton(int button, boolean pressed);
    }

    private final Context context;
    private final Listener listener;
    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattCharacteristic commandCharacteristic;
    private AdvertiseCallback advertiseCallback;
    private boolean started;
    private boolean starting;

    BluetoothFootPedalServer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    boolean isStarted() {
        return started || starting;
    }

    void start() {
        try {
            if (isStarted()) return;
            starting = true;
            BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                starting = false;
                listener.onStatus("Bluetooth vypnutý", false);
                return;
            }
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                starting = false;
                listener.onStatus("Telefon nepodporuje BLE advertising", false);
                return;
            }

            gattServer = manager.openGattServer(context, gattCallback);
            if (gattServer == null) {
                starting = false;
                listener.onStatus("Nelze otevřít BLE server", false);
                return;
            }

            BluetoothGattService service = new BluetoothGattService(
                    SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            commandCharacteristic = new BluetoothGattCharacteristic(
                    COMMAND_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE
                            | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE);
            service.addCharacteristic(commandCharacteristic);
            if (!gattServer.addService(service)) {
                stop();
                listener.onStatus("Nelze přidat pedálovou službu", false);
                return;
            }
            listener.onStatus("Spouštím BLE pedál…", false);
        } catch (SecurityException error) {
            starting = false;
            stop();
            listener.onStatus("Chybí oprávnění Bluetooth", false);
        } catch (RuntimeException error) {
            stop();
            listener.onStatus("BLE chyba: " + error.getMessage(), false);
        }
    }

    void stop() {
        try {
            if (advertiser != null && advertiseCallback != null) {
                advertiser.stopAdvertising(advertiseCallback);
            }
            if (gattServer != null) {
                gattServer.close();
            }
        } catch (SecurityException ignored) {
            // The OS may revoke Bluetooth access while the activity is stopped.
        } finally {
            started = false;
            starting = false;
            advertiser = null;
            advertiseCallback = null;
            gattServer = null;
            commandCharacteristic = null;
            listener.onStatus("Pedál vypnutý", false);
        }
    }

    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            if (status != BluetoothGatt.GATT_SUCCESS || advertiser == null) {
                listener.onStatus("BLE službu se nepodařilo spustit", false);
                stop();
                return;
            }

            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                    .build();

            advertiseCallback = new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    started = true;
                    starting = false;
                    listener.onStatus("BLE pedál připraven", false);
                }

                @Override
                public void onStartFailure(int errorCode) {
                    started = false;
                    starting = false;
                    stop();
                    listener.onStatus("BLE advertising selhal: " + errorCode, false);
                }
            };
            advertiser.startAdvertising(settings, data, advertiseCallback);
        }

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                listener.onStatus("BLE pedál připojen", true);
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                listener.onStatus("BLE pedál připraven", false);
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattCharacteristic characteristic, boolean preparedWrite,
                boolean responseNeeded, int offset, byte[] value) {
            if (responseNeeded && gattServer != null) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            }
            if (characteristic == null || !COMMAND_UUID.equals(characteristic.getUuid())
                    || value == null || value.length != 2 || offset != 0) {
                return;
            }

            int button = value[0] & 0xff;
            int state = value[1] & 0xff;
            if (button >= LEFT && button <= RIGHT && state <= 1) {
                listener.onButton(button, state == 1);
            }
        }
    };
}
