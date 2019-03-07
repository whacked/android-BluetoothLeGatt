/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.bluetoothlegatt;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.provider.MediaStore;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Activity for scanning and displaying available Bluetooth LE devices.
 */
public class DeviceScanActivity extends Activity {
    private BluetoothAdapter mBluetoothAdapter;
    private boolean mScanning;
    private Handler mHandler;

    private static final String TARGET_DEVICE_NAME = "SWAN";

    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_CODE_ASK_PERMISSIONS = 123;
    private static final int REQUEST_BARCODE_SCANNER = 49374;
    private static final int REQUEST_CAMERA_IMAGE = 2;

    // Stops scanning after 10 seconds.
    private static final long SCAN_PERIOD = 10000;

    private String mDeviceAddress;
    private EditText mEntryNameEditText;
    private TextView mLogFilePath;

    private ListView mBarCodeListView;
    private ArrayList<String> mBarCodeList;
    private ArrayAdapter<String> mBarCodeListAdapter;

    private ListView mPhotoFilePathListView;
    private ArrayList<String> mPhotoFilePathList;
    private ArrayAdapter<String> mPhotoFilePathListAdapter;

    public static final String TARGET_SERVICE_UUID = "0000ffb0";
    public static final String TARGET_CHARACTERISTIC_UUID = "0000ffb2";

    private TextView mConnectionState;
    private EditText mDataField;
    private TextView mScaleDataField;
    private boolean mConnected = false;
    private BluetoothGattCharacteristic mNotifyCharacteristic;

    private MocreoReadout mReadout;

    private BluetoothLeService mBluetoothLeService;
    private final static String TAG = DeviceScanActivity.class.getSimpleName();

    // Code to manage Service lifecycle.
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
            }
            // Automatically connects to the device upon successful start-up initialization.
            mBluetoothLeService.connect(mDeviceAddress);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
        }
    };

    // Handles various events fired by the Service.
    // ACTION_GATT_CONNECTED: connected to a GATT server.
    // ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
    // ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
    // ACTION_DATA_AVAILABLE: received data from the device.  This can be a result of read
    //                        or notification operations.
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                mConnected = true;
                mConnectionState.setText("Connected");
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                mConnected = false;
                mConnectionState.setText("Disconnected");
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Show all the supported services and characteristics on the user interface.
                initializeCharacteristic(mBluetoothLeService.getSupportedGattServices());
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                displayData(intent.getStringExtra(BluetoothLeService.EXTRA_DATA));
            }
        }
    };

    private void displayData(String data) {
        if (data != null) {
            try {
                mReadout = MocreoReadout.parseReadoutData(data);
                mScaleDataField.setText(mReadout.toString());
            } catch(MocreoReadout.InvalidReadoutException e) {

            }
        }
    }

    private JSONArray list2JsonArray(ArrayList<String> arr) {
        JSONArray out = new JSONArray();
        for(String element: arr) {
            out.put(element);
        }
        return out;
    }

    private void addPhotoFilePathEntry(String photoFilePathEntry) {
        mPhotoFilePathList.add(photoFilePathEntry);
        mPhotoFilePathListAdapter.notifyDataSetChanged();
        setListViewHeightBasedOnChildren(mPhotoFilePathListView);
    }

    private void addBarCodeEntry(String barCodeEntry) {
        mBarCodeList.add(barCodeEntry);
        mBarCodeListAdapter.notifyDataSetChanged();
        setListViewHeightBasedOnChildren(mBarCodeListView);
    }

    }

    private void updateConnectionState(final int resourceId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mConnectionState.setText(resourceId);
            }
        });
    }
    // Demonstrates how to iterate through the supported GATT Services/Characteristics.
    // In this sample, we populate the data structure that is bound to the ExpandableListView
    // on the UI.
    private void initializeCharacteristic(List<BluetoothGattService> gattServices) {
        if (gattServices == null) return;
        String uuid = null;

        BluetoothGattCharacteristic massCharacteristic = null;

        // Loops through available GATT Services.
        for (BluetoothGattService gattService : gattServices) {
            uuid = gattService.getUuid().toString();
            if(!uuid.startsWith(TARGET_SERVICE_UUID)) {
                continue;
            }
            List<BluetoothGattCharacteristic> gattCharacteristics =
                    gattService.getCharacteristics();

            // Loops through available Characteristics.
            for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                uuid = gattCharacteristic.getUuid().toString();
                if(!uuid.startsWith(TARGET_CHARACTERISTIC_UUID)) {
                    continue;
                }
                massCharacteristic = gattCharacteristic;
                break;
            }
        }

        // auto trigger reading the characteristic
        setBluetoothNotifyCharacteristic(massCharacteristic);
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getActionBar().setTitle(R.string.title_devices);
        mHandler = new Handler();

        // Use this check to determine whether BLE is supported on the device.  Then you can
        // selectively disable BLE-related features.
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
            finish();
        }

        requestPermissions(new String[]{
                        android.Manifest.permission.CAMERA,
                        android.Manifest.permission.BLUETOOTH,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION,
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                },
                REQUEST_CODE_ASK_PERMISSIONS);

        initMainView();

        // Initializes a Bluetooth adapter.  For API level 18 and above, get a reference to
        // BluetoothAdapter through BluetoothManager.
        final BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();

        // Checks if Bluetooth is supported on the device.
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, R.string.error_bluetooth_not_supported, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
    }

    private void setBluetoothNotifyCharacteristic(BluetoothGattCharacteristic characteristic) {
        if(characteristic == null) {
            return;
        }
        final int charaProp = characteristic.getProperties();
        if ((charaProp | BluetoothGattCharacteristic.PROPERTY_READ) > 0) {
            // If there is an active notification on a characteristic, clear
            // it first so it doesn't update the data field on the user interface.
            if (mNotifyCharacteristic != null) {
                mBluetoothLeService.setCharacteristicNotification(
                        mNotifyCharacteristic, false);
                mNotifyCharacteristic = null;
            }
            mBluetoothLeService.readCharacteristic(characteristic);
        }
        if ((charaProp | BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
            mNotifyCharacteristic = characteristic;
            mBluetoothLeService.setCharacteristicNotification(
                    characteristic, true);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        if (!mScanning) {
            menu.findItem(R.id.menu_stop).setVisible(false);
            menu.findItem(R.id.menu_scan).setVisible(true);
            menu.findItem(R.id.menu_refresh).setActionView(null);
        } else {
            menu.findItem(R.id.menu_stop).setVisible(true);
            menu.findItem(R.id.menu_scan).setVisible(false);
            menu.findItem(R.id.menu_refresh).setActionView(
                    R.layout.actionbar_indeterminate_progress);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_scan:
                scanLeDevice(true);
                break;
            case R.id.menu_stop:
                scanLeDevice(false);
                break;
        }
        return true;
    }

    // http://nex-otaku-en.blogspot.com/2010/12/android-put-listview-in-scrollview.html
    public static void setListViewHeightBasedOnChildren(ListView listView) {
        ListAdapter listAdapter = listView.getAdapter();
        if (listAdapter == null) {
            // pre-condition
            return;
        }

        int totalHeight = 0;
        int desiredWidth = View.MeasureSpec.makeMeasureSpec(listView.getWidth(), View.MeasureSpec.AT_MOST);
        for (int i = 0; i < listAdapter.getCount(); i++) {
            View listItem = listAdapter.getView(i, null, listView);
            listItem.measure(desiredWidth, View.MeasureSpec.UNSPECIFIED);
            totalHeight += listItem.getMeasuredHeight();
        }

        ViewGroup.LayoutParams params = listView.getLayoutParams();
        params.height = totalHeight + (listView.getDividerHeight() * (listAdapter.getCount() - 1));
        listView.setLayoutParams(params);
        listView.requestLayout();
    }

    private void initMainView() {
        LayoutInflater mInflator = DeviceScanActivity.this.getLayoutInflater();
        View view = mInflator.inflate(R.layout.listitem_device, null);
        setContentView(view);

        mBarCodeListView = findViewById(R.id.listview_barcode);
        mBarCodeList = new ArrayList<String>();
        mBarCodeListAdapter = new ArrayAdapter<String>(
                this,
                R.layout.listitem_plaintext,
                R.id.plaintext_data,
                mBarCodeList);
        mBarCodeListView.setAdapter(mBarCodeListAdapter);

        mPhotoFilePathListView = findViewById(R.id.listview_photo);
        mPhotoFilePathList = new ArrayList<String>();
        mPhotoFilePathListAdapter = new ArrayAdapter<String>(
                this,
                R.layout.listitem_plaintext,
                R.id.plaintext_data,
                mPhotoFilePathList);
        mPhotoFilePathListView.setAdapter(mPhotoFilePathListAdapter);

        // extended controls (buttons, entry input, etc)
        mLogFilePath = findViewById(R.id.data_file_path);
        mEntryNameEditText = findViewById(R.id.inp_entry_name);
        mDataField = findViewById(R.id.data_value);

        Button btnBarcode = findViewById(R.id.btn_barcode);
        Button btnPhoto = findViewById(R.id.btn_photo);

        findViewById(R.id.btn_clear_some).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View _) {
                clearMostSpecificInputs();
            }
        });
        findViewById(R.id.btn_clear_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View _) {
                clearUI();
            }
        });
        btnBarcode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View _) {
                Toast.makeText(getApplicationContext(),
                        "take barcode: " + mBarCodeList.size(),
                        Toast.LENGTH_SHORT).show();
                new IntentIntegrator(DeviceScanActivity.this).initiateScan();

//                addBarCodeEntry(new Date().toString());
            }
        });
        btnPhoto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View _) {
                Toast.makeText(getApplicationContext(),
                        "take photo: " + mPhotoFilePathList.size(),
                        Toast.LENGTH_SHORT).show();
                Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                startActivityForResult(cameraIntent, REQUEST_CAMERA_IMAGE);

                addPhotoFilePathEntry(new Date().toString());
            }
        });
        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View _) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ");
                JSONObject outStruct = new JSONObject();
                String outputString = "";
                try {
                    outStruct.put("time", sdf.format(new Date()));
                    outStruct.put("entry", mEntryNameEditText.getText());
                    if(mDataField.getText().length() > 0) {
                        outStruct.put("mass", mDataField.getText());
                    }

                    if(mBarCodeList.size() > 0) {
                        outStruct.put("barcode", list2JsonArray(mBarCodeList));
                    }
                    if(mPhotoFilePathList.size() > 0) {
                        outStruct.put("photo", list2JsonArray(mPhotoFilePathList));
                    }

                    outputString = outStruct.toString(0).replaceAll("\n", "");
                } catch (JSONException e) {
                    e.printStackTrace();

                    outputString = String.format(
                            "%s,%s,%s,%s,%s",
                            sdf.format(new Date()),
                            mEntryNameEditText.getText().toString(),
                            mDataField.getText().toString(),
                            mBarCodeTextView.getText(),
                            mPhotoFilePathTextView.getText()
                    );

                }

                File sdCard = Environment.getExternalStorageDirectory();
                String outputFilePath = mLogFilePath.getText().toString()
                        .replace("/sdcard", sdCard.getAbsolutePath());
                try {
                    FileOutputStream fOut = new FileOutputStream(new File(outputFilePath), true);
                    fOut.write(("\n" + outputString).getBytes());
                    fOut.close();
                    Toast.makeText(getApplicationContext(),
                            "saved: " + outputFilePath, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(getApplicationContext(),
                            "FAILED: " + e.toString(), Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Ensures Bluetooth is enabled on the device.  If Bluetooth is not currently enabled,
        // fire an intent to display a dialog asking the user to grant permission to enable it.
        if (!mBluetoothAdapter.isEnabled()) {
            if (!mBluetoothAdapter.isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            }
        }
        scanLeDevice(true);

        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        if (mBluetoothLeService != null) {
            final boolean result = mBluetoothLeService.connect(mDeviceAddress);
            Log.d(TAG, "Connect request result=" + result);
        }
    }

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d("activity result", String.format(
                "requestCode: %d, resultCode: %d",
                requestCode, resultCode));
        switch(requestCode) {
            case REQUEST_ENABLE_BT:
                if(resultCode == Activity.RESULT_CANCELED) {
                    Toast.makeText(getApplicationContext(),
                            "Bluetooth will not be available", Toast.LENGTH_LONG).show();
                }
                break;
            case REQUEST_CAMERA_IMAGE:
                if(resultCode == RESULT_OK) {
                    String lastImagePath = getLastImagePath();
                    if(lastImagePath == null) {
                    } else {
                        addPhotoFilePathEntry(new File(lastImagePath).getName());
                    }
                }
                break;
            case REQUEST_BARCODE_SCANNER:
                if(resultCode == RESULT_OK) {
                    IntentResult scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
                    if(scanResult != null) {
                        String barcodeText = String.format(
                                "%s:%s",
                                scanResult.getFormatName(),
                                scanResult.getContents());
                        addBarCodeEntry(barcodeText);
                    }
                }
                break;
            default:
                super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        scanLeDevice(false);
    }

    private void scanLeDevice(final boolean enable) {
        if (enable) {
            // Stops scanning after a pre-defined scan period.
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mScanning = false;
                    mBluetoothAdapter.stopLeScan(mLeScanCallback);
                    invalidateOptionsMenu();
                }
            }, SCAN_PERIOD);

            mScanning = true;
            mBluetoothAdapter.startLeScan(mLeScanCallback);
        } else {
            mScanning = false;
            mBluetoothAdapter.stopLeScan(mLeScanCallback);
        }
        invalidateOptionsMenu();
    }

    private void setupBTDeviceView(BluetoothDevice device) {
        final String deviceName = device.getName();

        TextView deviceNameView = findViewById(R.id.device_name);
        TextView deviceAddressView = findViewById(R.id.device_address);

        deviceNameView.setText(deviceName);
        // deviceAddressView.setText(device.getAddress());
        getActionBar().setTitle(device.getAddress());

        mConnectionState = findViewById(R.id.connection_state);
        mScaleDataField = findViewById(R.id.data_scale);

        Button btnTake = findViewById(R.id.btn_take);
        btnTake.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View _) {
                mDataField.setText(mScaleDataField.getText());
            }
        });
    }

    private void clearMostSpecificInputs() {
        mDataField.setText(R.string.no_data);
        mEntryNameEditText.setText(R.string.no_data);

        mBarCodeList.clear();
        mBarCodeListAdapter.notifyDataSetChanged();
        setListViewHeightBasedOnChildren(mBarCodeListView);

        mPhotoFilePathList.clear();
        mPhotoFilePathListAdapter.notifyDataSetChanged();
        setListViewHeightBasedOnChildren(mPhotoFilePathListView);
    }

    private void clearUI() {
        clearMostSpecificInputs();

        mInpQuickCat.setText(R.string.no_data);

        mSetTime.setText(R.string.set_time);
        mChkSetTime.setChecked(false);
    }

    // Device scan callback.
    private BluetoothAdapter.LeScanCallback mLeScanCallback =
            new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String deviceName = device.getName();
                    if(deviceName == null) {
                        return;
                    }
                    if(deviceName.equals(TARGET_DEVICE_NAME)) {
                        if (mScanning) {
                            mBluetoothAdapter.stopLeScan(mLeScanCallback);
                            mScanning = false;
                        }
                        setupBTDeviceView(device);
                        startRetrievingData(device.getAddress());
                    }
                }
            });
        }
    };

    private void startRetrievingData(String deviceAddress) {
        mDeviceAddress = deviceAddress;
        Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);
    }

    // https://stackoverflow.com/a/9067155
    private String getLastImagePath(){
        final String[] imageColumns = { MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA };
        final String imageOrderBy = MediaStore.Images.Media._ID + " DESC";
        Cursor imageCursor = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                imageColumns,
                null,
                null,
                imageOrderBy);
        if(imageCursor.moveToFirst()){
            int id = imageCursor.getInt(imageCursor.getColumnIndex(MediaStore.Images.Media._ID));
            String fullPath = imageCursor.getString(
                    imageCursor.getColumnIndex(MediaStore.Images.Media.DATA));
            Log.d(TAG, "getLastImageId::id " + id);
            Log.d(TAG, "getLastImageId::path " + fullPath);
            imageCursor.close();
            return fullPath;
        } else {
            return null;
        }
    }
}