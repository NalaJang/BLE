package com.example.bleex;


import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */
public class BluetoothLeService extends Service {
    private final static String TAG = "BluetoothLeService";

    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private String bluetoothDeviceAddress;
    private BluetoothGatt bluetoothGatt;
    private int connectionState = STATE_DISCONNECTED;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    public final static String ACTION_GATT_CONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED =
            "com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED";
    public final static String ACTION_DATA_AVAILABLE =
            "com.example.bluetooth.le.ACTION_DATA_AVAILABLE";
    public final static String EXTRA_DATA =
            "com.example.bluetooth.le.EXTRA_DATA";

    public final static UUID UUID_HEART_RATE_MEASUREMENT =
            UUID.fromString(SampleGattAttributes.HEART_RATE_MEASUREMENT);

    /*
     * 20220710 추가
     */
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static final UUID UART_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");

    //  TX, RX 두 채널을 통해 데이터를 주고 받는다.
    //  TX 를 통해 데이터를 보내고 RX 를 통해 데이터를 받는다.
    public static final UUID RX_CHAR_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID TX_CHAR_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");

    BluetoothGattCharacteristic RxChar;
    BluetoothGattCharacteristic TxChar;

    // Various callback methods defined by the BLE API.
    private final BluetoothGattCallback gattCallback =
            new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                    String intentAction;

                    if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                        intentAction = ACTION_GATT_CONNECTED;
                        connectionState = STATE_CONNECTED;

                        broadcastUpdate(intentAction);
                        Log.i(TAG, "Connected to GATT server.");

                        boolean result = bluetoothGatt.discoverServices();
                        Log.i(TAG, "discoverServices " + result);

                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        intentAction = ACTION_GATT_DISCONNECTED;
                        connectionState = STATE_DISCONNECTED;

                        broadcastUpdate(intentAction);
                        Log.i(TAG, "Disconnected from GATT server.");
                    }
                }

                @Override
                // New services discovered
                public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);

                        /*
                         * 20220710 추가
                         */
                        List<BluetoothGattService> services = bluetoothGatt.getServices();
                        for (BluetoothGattService service : services) {
                            //  UART 통신 서비스에 있는 TX, RX 특성을 가져온다.
                            if (service.getUuid().equals(UART_SERVICE_UUID)) {
                                RxChar = service.getCharacteristic(RX_CHAR_UUID);
                                TxChar = service.getCharacteristic(TX_CHAR_UUID);

                                break;
                            }
                        }

                    } else {
                        Log.w(TAG, "onServicesDiscovered received: " + status);
                    }
                }

                @Override
                // Result of a characteristic read operation
                public void onCharacteristicRead(BluetoothGatt gatt,
                                                 BluetoothGattCharacteristic characteristic,
                                                 int status) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "onCharacteristicRead");
                        broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
                    }
                }

                @Override
                public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                    broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
                }
            };



    private void broadcastUpdate(final String action) {
        final Intent intent = new Intent(action);
        sendBroadcast(intent);
    }


    private void broadcastUpdate(final String action,
                                 final BluetoothGattCharacteristic characteristic) {
        final Intent intent = new Intent(action);

        // This is special handling for the Heart Rate Measurement profile.
        // Data parsing is carried out as per profile specifications.
        // 샘플 UUID_HEART_RATE_MEASUREMENT 와 특성 값의 UUID 를 비교한다.
        // 비교값이 같을 경우
        if (UUID_HEART_RATE_MEASUREMENT.equals(characteristic.getUuid())) {
            int flag = characteristic.getProperties();
            int format = -1;

            // 특성 값의 속성이 16비트 또는 8비트를 변수 format 에 저장한다.
            if ((flag & 0x01) != 0) {
                format = BluetoothGattCharacteristic.FORMAT_UINT16;
                Log.d(TAG, "Heart rate format UINT16.");
            } else {
                format = BluetoothGattCharacteristic.FORMAT_UINT8;
                Log.d(TAG, "Heart rate format UINT8.");
            }
            final int heartRate = characteristic.getIntValue(format, 1);
            Log.d(TAG, String.format("Received heart rate: %d", heartRate));
            intent.putExtra(EXTRA_DATA, String.valueOf(heartRate));

        }
        // 비교값이 다를 경우
        else {
            // For all other profiles, writes the data formatted in HEX.
            final byte[] data = characteristic.getValue();
            if (data != null && data.length > 0) {
                final StringBuilder stringBuilder = new StringBuilder(data.length);

                // 특성 값을 바이트 배열에 넣고
                for (byte byteChar : data) {
                    // 바이트 배열을 16진수로 변환한다.
                    stringBuilder.append(String.format("%02X ", byteChar));
                }
                // stringBuilder 에 넣어준다.
                intent.putExtra(EXTRA_DATA, new String(data) + "\n" + stringBuilder.toString());
            }
        }
        sendBroadcast(intent);
    }

    public class LocalBinder extends Binder {
        BluetoothLeService getService() {
            return BluetoothLeService.this;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // After using a given device, you should make sure that BluetoothGatt.close() is called
        // such that resources are cleaned up properly.  In this particular example, close() is
        // invoked when the UI is disconnected from the Service.
        close();
        return super.onUnbind(intent);
    }

    private final IBinder binder = new LocalBinder();


    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    public boolean initialize() {
        Log.d(TAG, "initialize");
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        if (bluetoothManager == null) {
            bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (bluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager");
                return false;
            }
        }

        bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter");
            return false;
        }

        return true;
    }



    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param address The device address of the destination device.
     *
     * @return Return true if the connection is initiated successfully. The connection result
     *         is reported asynchronously through the
     *         {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     *         callback.
     */
    public boolean connect(final String address) {
        if (bluetoothAdapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address");
            return false;
        }

        // Previously connected device.  Try to reconnect.
        if (bluetoothDeviceAddress != null && address.equals(bluetoothDeviceAddress)
                && bluetoothGatt != null) {
            Log.d(TAG, "Trying to use an existing bluetoothGatt for connection");

            if (bluetoothGatt.connect()) {
                connectionState = STATE_CONNECTING;
                return true;
            } else {
                return false;
            }
        }

        final BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.w(TAG, "Device not found. Unable to connect.");
            return false;
        }

        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        bluetoothGatt = device.connectGatt(this, false, gattCallback);
        Log.d(TAG, "Trying to create a new connection.");
        bluetoothDeviceAddress = address;
        connectionState = STATE_CONNECTING;
        return true;

    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public void disconnect() {
        if (bluetoothAdapter == null || bluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        bluetoothGatt.disconnect();
    }


    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    public void close() {
        if (bluetoothGatt == null) {
            return;
        }
        bluetoothGatt.close();
        bluetoothGatt = null;
    }

    /**
     * Request a read on a given {@code BluetoothGattCharacteristic}.
     * The read result is reported asynchronously through
     * the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
     * callback.
     *
     * @param characteristic The characteristic to read from.
     */
    public void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if( bluetoothAdapter == null || bluetoothGatt == null ) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        bluetoothGatt.readCharacteristic(characteristic);
    }


    /*
     * 20220710 추가
     * BLE 장치에 패킷 전송
     */
    public void writeCharacteristic(int commandPacket) {
        int CMD = 0, checkSum = 0;

        if( bluetoothAdapter == null || bluetoothGatt == null ) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }

        if(commandPacket == 0 ) {
            CMD = 0X00; //  parameter Packet 만 전송 요청
            Log.d(TAG, "parameter Packet 요청");
        } else if( commandPacket == 1 ) {
            CMD = 0X01; //  Saved Data 요청
            Log.d(TAG, "Saved Data 요청");
        } else if( commandPacket == 2 ) {
            CMD = 0X02; //  parameter Packet, PPG Raw Packet 모두 요청
            Log.d(TAG, "데이터, ppg 요청");
        }


        //  전송할 패킷
        final byte[] value = new byte[4];

        value[0] = (byte) (0xE8);   //STX
        value[1] = (byte) (CMD);    //CMD

        checkSum = (value[0] +  value[1]) & 0x000000FF;
        value[2] = (byte) (checkSum);   //checkSum  (0xE9)
        value[3] = (byte) (0x0A);       //ETX

        TxChar.setValue(value);

        bluetoothGatt.writeCharacteristic(TxChar);
    }


    /**
     * Enables or disables notification on a give characteristic.
     *
     * @param characteristic Characteristic to act on.
     * @param enabled If true, enable notification.  False otherwise.
     */
    public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic, boolean enabled) {
        if( bluetoothAdapter == null || bluetoothGatt == null ) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        bluetoothGatt.setCharacteristicNotification(characteristic, enabled);

        // This is specific to Heart Rate Measurement.
        if( UUID_HEART_RATE_MEASUREMENT.equals(characteristic.getUuid()) ) {
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                    UUID.fromString(SampleGattAttributes.CLIENT_CHARACTERISTIC_CONFIG));
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            bluetoothGatt.writeDescriptor(descriptor);
        }
    }

    /**
     * Retrieves a list of supported GATT services on the connected device. This should be
     * invoked only after {@code BluetoothGatt#discoverServices()} completes successfully.
     *
     * @return A {@code List} of supported services.
     */
    public List<BluetoothGattService> getSupportedGattServices() {
        if (bluetoothGatt == null) return null;

        return bluetoothGatt.getServices();
    }

}
