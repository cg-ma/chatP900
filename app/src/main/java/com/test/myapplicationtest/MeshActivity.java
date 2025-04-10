package com.test.myapplicationtest;

import androidx.appcompat.app.AppCompatActivity;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;

public class MeshActivity extends AppCompatActivity {
    private static final String ACTION_USB_PERMISSION = "com.example.USB_PERMISSION";
    private UsbManager usbManager;
    private UsbSerialPort serialPort;

    private TextView tvReceived, tvDeviceInfo;
    private EditText etTargetAddr, etMessage;
    private Button btnSendUnicast, btnSendBroadcast;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_mesh);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        tvReceived = findViewById(R.id.tv_received);
        tvDeviceInfo = findViewById(R.id.tv_device_info);
        etTargetAddr = findViewById(R.id.et_target_addr);
        etMessage = findViewById(R.id.et_message);
        btnSendUnicast = findViewById(R.id.btn_send_unicast);
        btnSendBroadcast = findViewById(R.id.btn_send_broadcast);

        findUsbDevice();

        btnSendUnicast.setOnClickListener(v -> {
                String addr = etTargetAddr.getText().toString().trim();
                String msg = etMessage.getText().toString().trim();
                sendMeshTo(addr, msg);

        });

        btnSendBroadcast.setOnClickListener(v -> {
            String msg = etMessage.getText().toString().trim();
            sendMeshBroadcast(msg);
        });

        IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        registerReceiver(usbReceiver, filter);
    }

    private void findUsbDevice() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        for (UsbDevice device : deviceList.values()) {
            Log.d("USB", "找到设备: VendorId=" + device.getVendorId() + ", ProductId=" + device.getProductId());
            requestUsbPermission(device);
        }
    }

    private void requestUsbPermission(UsbDevice device) {
        PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE);
        usbManager.requestPermission(device, permissionIntent);
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    String deviceInfo = "设备插入: VendorId=" + device.getVendorId() +
                            ", ProductId=" + device.getProductId();
                    tvDeviceInfo.setText(deviceInfo);
                    requestUsbPermission(device);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                tvDeviceInfo.setText("设备已拔出");
            } else if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    openSerialPort(device);
                }
            }
        }
    };

    private void openSerialPort(UsbDevice device) {
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            showToast("未找到合适的驱动");
            return;
        }

        List<UsbSerialPort> ports = driver.getPorts();
        if (ports.isEmpty()) {
            showToast("没有可用的串口");
            return;
        }

        serialPort = ports.get(0);
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            showToast("无法打开设备");
            return;
        }

        try {
            serialPort.open(connection);
            serialPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            startReading();
        } catch (IOException e) {
            Log.e("USB", "打开串口失败", e);
        }
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void startReading() {
        new Thread(() -> {
            byte[] buffer = new byte[64];
            while (true) {
                try {
                    int len = serialPort.read(buffer, 1000);
                    if (len > 0) {
                        String receivedData = new String(buffer, 0, len);
                        runOnUiThread(() -> tvReceived.setText("接收到: " + receivedData));
                    }
                } catch (IOException e) {
                    break;
                }
            }
        }).start();
    }

    private void sendMeshTo(String destAddr, String message) {
        new Thread(() -> {
            try {
                serialPort.write("+++".getBytes(), 1000);
                Thread.sleep(100);
                serialPort.write(("ATS140=" + destAddr + "\r\n").getBytes(), 1000);
                Thread.sleep(100);
                serialPort.write("AT&WA\r\n".getBytes(), 1000);
                Thread.sleep(100);
                serialPort.write((message + "\r\n").getBytes(), 1000);
                Log.d("MESH", "单播发送至 " + destAddr + ": " + message);
            } catch (Exception e) {
                Log.e("MESH", "单播发送失败", e);
                runOnUiThread(() -> showToast("单播失败: " + e.getMessage()));
            }
        }).start();
    }

    private void sendMeshBroadcast(String message) {
        new Thread(() -> {
            try {
                serialPort.write("+++".getBytes(), 1000);
                Thread.sleep(100);
                serialPort.write("ATS140=FF:FF:FF:FF:FF:FF\r\n".getBytes(), 1000);
                Thread.sleep(100);
                serialPort.write("AT&WA\r\n".getBytes(), 1000);
                Thread.sleep(100);
                serialPort.write((message + "\r\n").getBytes(), 1000);
                Log.d("MESH", "广播发送: " + message);
            } catch (Exception e) {
                Log.e("MESH", "广播发送失败", e);
                runOnUiThread(() -> showToast("广播失败: " + e.getMessage()));
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (serialPort != null) {
                serialPort.close();
            }
        } catch (IOException e) {
            Log.e("USB", "串口关闭失败", e);
        }
    }
}
