package ai.rotor.rotorvehicle;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import butterknife.BindView;
import butterknife.ButterKnife;
import timber.log.Timber;

import android.os.ParcelUuid;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;


import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;

import static ai.rotor.rotorvehicle.RotorUtils.ROTOR_UUID;

public class MainActivity extends Activity {
    private static final int MY_PERMISSIONS_REQUEST_ACCESS_COARSE_LOCATION = 1;
    private static final int REQUEST_ENABLE_BT = 2;
    private static final int REQUEST_PAIR_BT = 3;
    private static final int DISCOVERABLE_DURATION = 30;

    private BluetoothManager mBluetoothManager;
    private BluetoothDevice mPairedBTDevice;
    private Set<BluetoothDevice> mPairedDevices;
    private BluetoothService mBluetoothService;

    //BLE
    private BluetoothGattServer mGattServer;
    private BluetoothLeAdvertiser mAdvertiser;
    AdvertiseCallback mAdvertiserCallback;

    private Boolean connected;
    private Timber.DebugTree debugTree = new Timber.DebugTree();
    private final BroadcastReceiver mReceiver = new RotorBroadcastReceiver();
    private RotorCtlService mRotorCtlService;

    @BindView(R.id.pairBtn) Button mPairBtn;
    @BindView(R.id.statusTv) TextView mStatusTv;
    @BindView(R.id.commandTv) TextView mCommandTv;
    @BindView(R.id.pairingProgressBar) ProgressBar mPairingProgressBar;
    @BindView(R.id.autoBtn) Button mAutoBtn;
    @BindView(R.id.autoStatusTv) TextView mAutoStatusTv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);
        Timber.plant(debugTree);

        Timber.d("onCreate, thread ID: %s", Thread.currentThread().getId());

        // Setup GUI
        mPairingProgressBar.setVisibility(View.INVISIBLE);
        mBluetoothManager.getAdapter().setName("Vehicle");

        mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothManager.getAdapter().setName("Vehicle");

        mPairedDevices = mBluetoothManager.getAdapter().getBondedDevices();
        if (mPairedDevices == null || mPairedDevices.size() == 0) {
            showDisabled();
        } else if (mPairedDevices.size() == 1) {
            mPairedBTDevice = mPairedDevices.iterator().next();
            showEnabled();
        } else {
            showMultipleDevices();
        }

        connected = false;

        // Start the Rotor control service thread
        mRotorCtlService = new RotorCtlService(this);
        mRotorCtlService.run();
        updateAutoBtnStyle();

        mPairBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPairedDevices = mBluetoothManager.getAdapter().getBondedDevices();
                Timber.d("Pair button pressed");
                if (mPairedDevices.size() > 0) {
                    Timber.d("Still paired to devices: " + mPairedDevices);
                    for (BluetoothDevice device : mPairedDevices) {
                        unpairDevice(device);
                    }
                    showDisabled();
                } else {
                    if (!mBluetoothManager.getAdapter().isEnabled()) {
                        Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                        startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
                        Timber.d("Starting enabling activity");
                    } else {
                        makeDiscoverable();
                    }

                }
            }
        });

        mAutoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mRotorCtlService.getRotorState() == RotorCtlService.State.HOMED) {
                    goToAuto();
                } else {
                    goToHomed();
                }
            }
        });

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(RotorUtils.ACTION_STREAMS_ACQUIRED);
        filter.addAction(RotorUtils.ACTION_DISCONNECTED);
        filter.addAction(RotorUtils.ACTION_MESSAGE_RECEIVED);

        registerReceiver(mReceiver, filter);
        LocalBroadcastManager.getInstance(this).registerReceiver(mReceiver, filter);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mReceiver);

        //shutdown Bluetooth advertising and GATT server
        if (mAdvertiser != null){
            mAdvertiser.stopAdvertising(mAdvertiserCallback);
        }
        if (mGattServer != null){
            mGattServer.close();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Timber.d("OnActivityResult request code: " + String.valueOf(requestCode) + ", result code: " + String.valueOf(resultCode));

        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == Activity.RESULT_CANCELED) {
                Timber.d("OnActivityResult, enabling cancelled");
                showDisabled();
            } else {
                Timber.d("OnActivityResult, enabled");
                makeDiscoverable();
            }
        }
    }

    private void makeDiscoverable() {
        Timber.d("Making discoverable...");

        mAdvertiser = mBluetoothManager.getAdapter().getBluetoothLeAdvertiser();
        GSCallback gsCallback = new GSCallback();
        mGattServer = mBluetoothManager.openGattServer(this, gsCallback);
        BluetoothGattService rotorService = new BluetoothGattService(ROTOR_UUID,BluetoothGattService.SERVICE_TYPE_PRIMARY);
        mGattServer.addService(rotorService);
        mAdvertiserCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                super.onStartSuccess(settingsInEffect);
                showPairing();
            }

            @Override
            public void onStartFailure(int errorCode) {
                super.onStartFailure(errorCode);
                showDisabled();
                hideProgress();
                Timber.d("Advertise.onStartFailure errorcode: " + errorCode);
            }
        };

        AdvertiseSettings settings = new AdvertiseSettings
                .Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();
        AdvertiseData advertiseData = new AdvertiseData
                .Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(new ParcelUuid(rotorService.getUuid()))
                .build();

        mAdvertiser.startAdvertising(settings, advertiseData, mAdvertiserCallback);
    }


    private void showPairing() {
        Timber.d("Show Pairing");
        mStatusTv.setText(getString(R.string.ui_starting_discoverability));
        mStatusTv.setTextColor(Color.GRAY);
        mPairingProgressBar.setVisibility(View.VISIBLE);

        mPairBtn.setEnabled(false);
    }

    private void showEnabled() {
        String name = mPairedBTDevice.getName();
        mStatusTv.setText("Bluetooth state: Paired to " + name + ", waiting for connection...");
        mStatusTv.setTextColor(Color.BLUE);
        mPairingProgressBar.setVisibility(View.INVISIBLE);

        mPairBtn.setText(getString(R.string.ui_unpair));
        mPairBtn.setEnabled(true);
        mCommandTv.setVisibility(View.VISIBLE);
        mCommandTv.setText("");
    }

    private void showDisabled() {
        mStatusTv.setText("Bluetooth state: UNPAIRED");
        mStatusTv.setTextColor(Color.GRAY);

        mPairBtn.setText(getString(R.string.ui_pair));
        mPairBtn.setEnabled(true);
        mCommandTv.setVisibility(View.INVISIBLE);
    }

    private void showMultipleDevices() {
        mStatusTv.setText(getString(R.string.ui_too_many));
        mStatusTv.setTextColor(Color.RED);

        mPairBtn.setText(getString(R.string.ui_unpair));
        mCommandTv.setVisibility(View.INVISIBLE);
    }

    private void showConnected() {
        String name = mPairedBTDevice.getName();
        mStatusTv.setText("Bluetooth state: Connected to " + name);
        mStatusTv.setTextColor(Color.BLUE);
        mPairingProgressBar.setVisibility(View.INVISIBLE);

        mPairBtn.setText(getString(R.string.ui_unpair));
        mPairBtn.setEnabled(true);
        mCommandTv.setVisibility(View.VISIBLE);
        mCommandTv.setText("");
    }

    private void updateAutoBtnStyle() {
        if (mRotorCtlService.getRotorState() == RotorCtlService.State.HOMED) {
            showHomed();
        } else if (mRotorCtlService.getRotorState() == RotorCtlService.State.MANUAL) {
            showManual();
        } else {
            showAuto();
        }
    }

    private void showHomed() {
        mAutoStatusTv.setText("Rotor State: " + mRotorCtlService.getRotorState());
        mAutoBtn.setText("AUTO MODE!");
    }

    private void showAuto() {
        mAutoStatusTv.setText("Rotor State: " + mRotorCtlService.getRotorState());
        mAutoBtn.setText("HOME ROTOR");
    }

    private void showManual() {
        mAutoStatusTv.setText("Rotor State: " + mRotorCtlService.getRotorState());
        mAutoBtn.setText("HOME ROTOR");
    }

    private void hideProgress() {
        mPairingProgressBar.setVisibility(View.INVISIBLE);
    }

    private void unpairDevice(BluetoothDevice device) {
        try {
            Method m = device.getClass().getMethod("removeBond", (Class[]) null);
            m.invoke(device, (Object[]) null);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            e.printStackTrace();
        }
    }

    private void goToAuto() {
        Timber.d("Going to autonomous mode...");
        mRotorCtlService.setState(RotorCtlService.State.AUTONOMOUS);
        updateAutoBtnStyle();
    }

    private void goToHomed() {
        Timber.d("Homing...");
        mRotorCtlService.setState(RotorCtlService.State.HOMED);
        updateAutoBtnStyle();
    }

    private void goToManual() {
        Timber.d("Going to manual mode...");
        mRotorCtlService.setState(RotorCtlService.State.MANUAL);
        updateAutoBtnStyle();
    }


    class GSCallback extends BluetoothGattServerCallback {

    }

    class RotorBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Timber.d("In onReceive, action: " + action);
            if (BluetoothAdapter.ACTION_SCAN_MODE_CHANGED.equals(action)) {
                int scanMode = intent.getIntExtra(BluetoothAdapter.EXTRA_SCAN_MODE, BluetoothAdapter.ERROR);
                Timber.d("Scan mode value: %s", String.valueOf(scanMode));

                switch (scanMode) {
                    case BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE:
                        showPairing();
                        goToHomed();
                        break;
                    case BluetoothAdapter.SCAN_MODE_CONNECTABLE:
                        hideProgress();
                        mPairedDevices = mBluetoothManager.getAdapter().getBondedDevices();
                        if (mPairedDevices == null || mPairedDevices.size() == 0) {
                            showDisabled();
                        } else if (mPairedDevices.size() == 1 && !connected) {
                            mPairedBTDevice = mPairedDevices.iterator().next();
                            showEnabled();
                        }
                        break;
                }
            }

            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice mDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

                if (mDevice.getBondState() == BluetoothDevice.BOND_BONDED) {
                    Timber.d("BroadcastReceiver: BOND_BONDED.");
                    mPairedBTDevice = mDevice;
                    showEnabled();
                    hideProgress();
                    mBluetoothService = new BluetoothService();
                    mBluetoothService.startClient(MainActivity.this);
                }
                if (mDevice.getBondState() == BluetoothDevice.BOND_BONDING) {
                    Timber.d("BroadcastReceiver: BOND_BONDING.");
                }
                if (mDevice.getBondState() == BluetoothDevice.BOND_NONE) {
                    Timber.d("BroadcastReceiver: BOND_NONE.");
                    mPairedDevices = mBluetoothManager.getAdapter().getBondedDevices();
                    if (mPairedDevices == null || mPairedDevices.size() == 0) {
                        showDisabled();
                    } else if (mPairedDevices.size() == 1) {
                        mPairedBTDevice = mPairedDevices.iterator().next();
                        showEnabled();
                    }
                }
            }

            if (RotorUtils.ACTION_STREAMS_ACQUIRED.equals(action)) {
                connected = true;
                showConnected();
                goToManual();
            }

            if (RotorUtils.ACTION_DISCONNECTED.equals(action)) {
                connected = false;
                showEnabled();
                goToHomed();
            }

            if (RotorUtils.ACTION_MESSAGE_RECEIVED.equals(action)) {
                String cmd = intent.getStringExtra(RotorUtils.EXTRA_CMD);

                if (cmd.charAt(0) == '_') {
                    if (cmd.charAt(1) == 'A') {
                        goToHomed();
                        goToAuto();
                    } else {
                        goToHomed();
                        goToManual();
                    }
                    return;
                }
                mRotorCtlService.sendCommand(cmd);
                mCommandTv.setText(cmd);
            }
        }
    }
}