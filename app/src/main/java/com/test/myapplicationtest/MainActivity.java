package com.test.myapplicationtest;

import static java.lang.Thread.sleep;

import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String ACTION_USB_PERMISSION = "com.example.USB_PERMISSION";
    private UsbManager usbManager;
    private UsbSerialPort serialPort;
    private TextView tvReceived;
    private TextView tvDeviceInfo;
    private Button btnSend;        // 定时发送按钮
    private Button btnSendOnce;    // 单次发送按钮
    private Button btnSendImage;
    private TextView tvProgress;
    private byte[] fixedImageBytes;
    private EditText etInput;
    private TextView tvSpeed;
    private ImageView ivReceivedImage;
    private ByteArrayOutputStream imageBuffer = new ByteArrayOutputStream();
    private boolean isReceivingImage = false;
    private int expectedImageSize = 0;
    private static final String LINE_BREAK = "\n";
    private StringBuilder receivedText = new StringBuilder();
    private byte[] audioData;
    private MediaPlayer mediaPlayer;
    private Button btnPlay;
    private ProgressBar progressAudioReceive;
    private TextView tvAudioStatus;
    private byte[] receivedAudioData;
    private ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
    private int expectedAudioSize = 0;
    private boolean isReceivingAudio = false;
    private Button btnSendAudio;
    private ProgressBar progressAudioSend;
    private TextView tvAudioSendStatus;

    private static final byte TEXT_FLAG = 0x31;
    private static final byte IMAGE_FLAG = 0x32;
    private static final byte AUDIO_FLAG = 0x33;
    private static final byte IMAGE_FLAG_END = 0x34;

    private Handler sendHandler = new Handler(Looper.getMainLooper());
    private Runnable sendRunnable;
    private int sendCount = 0;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 初始化USB管理器和UI控件
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        tvReceived = findViewById(R.id.tv_received);
        tvDeviceInfo = findViewById(R.id.tv_device_info);
        btnSendImage = findViewById(R.id.btn_send_image);
        btnSend = findViewById(R.id.btn_send);
        btnSendOnce = findViewById(R.id.btn_send_once);
        tvProgress = findViewById(R.id.tv_progress);
        ivReceivedImage = findViewById(R.id.iv_received_image);
        etInput = findViewById(R.id.et_input);
        btnSendAudio = findViewById(R.id.btn_send_audio);
        progressAudioReceive = findViewById(R.id.progress_audio_receive);
        tvAudioStatus = findViewById(R.id.tv_audio_status);
        btnPlay = findViewById(R.id.btn_play);
        progressAudioSend = findViewById(R.id.progress_audio_send);
        tvAudioSendStatus = findViewById(R.id.tv_audio_send_status);
        tvSpeed = findViewById(R.id.tv_speed);

        // 加载固定图片到内存
        loadFixedImage();
        // 加载固定音频到内存
        loadAudioFile();

        // USB设备检测
        findUsbDevice();

        btnPlay.setOnClickListener(v -> playAudio());

        // 定时发送按钮逻辑（持续发送 "1"）
        btnSend.setOnClickListener(v -> {
            if (sendRunnable != null) {
                sendHandler.removeCallbacks(sendRunnable);
                sendRunnable = null;
                Toast.makeText(this, "已停止发送", Toast.LENGTH_SHORT).show();
                btnSend.setText("发送");
            } else {
                sendCount = 0;
                receivedText.setLength(0); // 可选：清空文本框
                tvReceived.setText("");    // 可选：清空显示

                sendRunnable = new Runnable() {
                    @Override
                    public void run() {
                        sendTextData("111111111111111"); // 定时发送 "1"
                        sendCount++;
                        runOnUiThread(() -> {
                            receivedText.append("发送次数: ").append(sendCount).append(LINE_BREAK);
                            tvReceived.setText(receivedText.toString());
                            ((ScrollView) tvReceived.getParent()).fullScroll(View.FOCUS_DOWN);
                        });
                        sendHandler.postDelayed(this, 500);
                    }
                };
                sendHandler.post(sendRunnable);
                Toast.makeText(this, "开始定时发送", Toast.LENGTH_SHORT).show();
                btnSend.setText("停止");
            }
        });

        // 单次发送按钮逻辑（发送文本框内容）
        btnSendOnce.setOnClickListener(v -> {
            String inputText = etInput.getText().toString().trim();
            if (!inputText.isEmpty()) {
                sendTextData(inputText); // 单次发送文本框内容
                runOnUiThread(() -> {
                    receivedText.append("单次发送: ").append(inputText).append(LINE_BREAK);
                    tvReceived.setText(receivedText.toString());
                    ((ScrollView) tvReceived.getParent()).fullScroll(View.FOCUS_DOWN);
                });
                Toast.makeText(this, "已发送一次", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "请输入内容后再发送", Toast.LENGTH_SHORT).show();
            }
        });

        // 发送图片
        btnSendImage.setOnClickListener(v -> {
            if (fixedImageBytes != null) {
                sendImageData(fixedImageBytes);
            } else {
                Toast.makeText(this, "图片未加载", Toast.LENGTH_SHORT).show();
            }
        });

        // 发送音频
        btnSendAudio.setOnClickListener(v -> {
            runOnUiThread(() -> {
                tvAudioSendStatus.setText("发送中...");
                progressAudioSend.setProgress(0);
            });
            new Thread(() -> sendAudioData()).start();
        });

        // 注册广播接收器
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        registerReceiver(usbReceiver, filter);
    }

    private void loadFixedImage() {
        try {
            InputStream is = getAssets().open("test30kB.jpg");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[1024];
            int nRead;
            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            fixedImageBytes = buffer.toByteArray();
            tvProgress.setText("图片已加载，大小: " + fixedImageBytes.length + "字节");
            buffer.close();
        } catch (IOException e) {
            Log.e("USB", "加载图片失败", e);
            tvProgress.setText("图片加载失败");
        }
    }

    private void sendImageData(byte[] imageData) {
        if (serialPort == null) {
            Toast.makeText(this, "USB设备未连接", Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            try {
                if (serialPort == null || !serialPort.isOpen()) {
                    runOnUiThread(() ->
                            Toast.makeText(this, "USB未连接", Toast.LENGTH_SHORT).show());
                    return;
                }
                if (imageData == null || imageData.length == 0) {
                    runOnUiThread(() ->
                            Toast.makeText(this, "图片数据无效", Toast.LENGTH_SHORT).show());
                    return;
                }

                byte[] sizeInfo = ByteBuffer.allocate(5)
                        .put(IMAGE_FLAG)
                        .putInt(imageData.length)
                        .array();
                serialPort.write(sizeInfo, 1000);

                int chunkSize = 63;
                int totalPackets = (int) Math.ceil((double) imageData.length / chunkSize);

                for (int i = 0; i < totalPackets; i++) {
                    sleep(3000);
                    int start = i * chunkSize;
                    int end = Math.min(start + chunkSize, imageData.length);
                    byte[] chunk = Arrays.copyOfRange(imageData, start, end);

                    if (chunk.length == 0) continue;

                    byte[] packet = new byte[chunk.length + 1];
                    packet[0] = (i == totalPackets - 1) ? IMAGE_FLAG_END : IMAGE_FLAG;
                    System.arraycopy(chunk, 0, packet, 1, chunk.length);
                    serialPort.write(packet, 1000);

                    final int progress = (i + 1) * 100 / totalPackets;
                    runOnUiThread(() -> tvProgress.setText("发送进度: " + progress + "%"));
                }

                runOnUiThread(() -> Toast.makeText(this, "图片发送完成", Toast.LENGTH_SHORT).show());
            } catch (IOException | InterruptedException e) {
                Log.e("USB", "图片发送失败", e);
                runOnUiThread(() -> tvProgress.setText("发送失败: " + e.getMessage()));
            }
        }).start();
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
                    Log.d("USB", "设备插入: " + deviceInfo);
                    requestUsbPermission(device);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    tvDeviceInfo.setText("设备已拔出");
                    Log.d("USB", "设备拔出");
                }
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
            Log.e("USB", "未找到合适的驱动");
            showToast("未找到合适的驱动");
            return;
        }

        List<UsbSerialPort> ports = driver.getPorts();
        if (ports.isEmpty()) {
            Log.e("USB", "没有可用的串口");
            showToast("没有可用的串口");
            return;
        }

        serialPort = ports.get(0);
        UsbDeviceConnection connection = usbManager.openDevice(device);

        if (connection == null) {
            Log.e("USB", "无法打开设备");
            showToast("无法打开设备");
            return;
        }

        try {
            serialPort.open(connection);
            serialPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            Log.d("USB", "串口已打开");
            startReading();
        } catch (IOException e) {
            Log.e("USB", "打开串口失败", e);
        }
    }

    private void sendTextData(String data) {
        if (serialPort != null) {
            try {
                byte[] textBytes = data.getBytes(StandardCharsets.UTF_8);
                // 对于单次发送，使用原始文本内容；对于定时发送，已在调用时固定为 "1"
                byte[] packet = new byte[textBytes.length + 1];
                packet[0] = TEXT_FLAG;
                System.arraycopy(textBytes, 0, packet, 1, textBytes.length);

                serialPort.write(packet, 1000);

                Log.d("USB", "发送成功: " + data);
            } catch (IOException e) {
                Log.e("USB", "发送失败", e);
                runOnUiThread(() ->
                        Toast.makeText(this, "发送失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        } else {
            Toast.makeText(this, "USB未连接", Toast.LENGTH_SHORT).show();
        }
    }

    private void startReading() {
        new Thread(() -> {
            byte[] buffer = new byte[1024];
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    int len = serialPort.read(buffer, 1000);
                    if (len > 1) {
                        byte flag = buffer[0];
                        byte[] data = Arrays.copyOfRange(buffer, 1, len);
                        if (flag == TEXT_FLAG) {
                            processReceiveDataText(data);
                        } else if (flag == IMAGE_FLAG) {
                            processReceivedDataImage(data, len - 1);
                        } else {
                            processReceivedDataAudio(data, len - 1);
                        }
                    }
                } catch (IOException e) {
                    Log.e("USB", "接收中断", e);
                    break;
                }
            }
        }).start();
    }

    private long lastReceiveTime = 0;
    private double currentSpeed = 0;
    private double[] speedHistory = new double[5];
    private int speedIndex = 0;

//    private void processReceiveDataText(byte[] receivedBytes) {
//        try {
//            long currentTime = System.currentTimeMillis();
//            int currentByteCount = receivedBytes.length;
//
//            if (lastReceiveTime > 0) {
//                double intervalSec = (currentTime - lastReceiveTime) / 1000.0;
//                currentSpeed = currentByteCount / intervalSec / 1024;
//                speedHistory[speedIndex % 5] = currentSpeed;
//                speedIndex++;
//                currentSpeed = Arrays.stream(speedHistory).average().orElse(0);
//            }
//
//            lastReceiveTime = currentTime;
//
//            String text = new String(receivedBytes, StandardCharsets.UTF_8);
//            runOnUiThread(() -> {
//                tvSpeed.setText(formatSpeed(currentSpeed));
//                receivedText.append(text).append(LINE_BREAK);
//                tvReceived.setText(receivedText.toString());
//                ((ScrollView) tvReceived.getParent()).fullScroll(View.FOCUS_DOWN);
//            });
//        } catch (Exception e) {
//            Log.e("USB", "解码失败", e);
//        }
//    }

    private static final int MAX_LOG_LENGTH = 10000; // 最大保留字符数
    private static final int TRIM_THRESHOLD = 8000;  // 达到此长度时开始清理
//    private final StringBuilder receivedText = new StringBuilder();
    private int lastTextLength = 0;  // 记录上一次更新TextView时的文本长度
    private long lastUpdateTime = 0; // 记录上一次更新时间（配合使用）

    private void processReceiveDataText(byte[] receivedBytes) {
        try {
            long currentTime = System.currentTimeMillis();
            int currentByteCount = receivedBytes.length;

            // 计算速度的逻辑保持不变
            if (lastReceiveTime > 0) {
                double intervalSec = (currentTime - lastReceiveTime) / 1000.0;
                currentSpeed = currentByteCount / intervalSec / 1024;
                speedHistory[speedIndex % 5] = currentSpeed;
                speedIndex++;
                currentSpeed = Arrays.stream(speedHistory).average().orElse(0);
            }
            lastReceiveTime = currentTime;

            String text = new String(receivedBytes, StandardCharsets.UTF_8);

            runOnUiThread(() -> {
                // 更新速度显示
                tvSpeed.setText(formatSpeed(currentSpeed));

                // 优化日志处理
                synchronized (receivedText) {
                    // 检查并清理过长的日志
                    if (receivedText.length() > MAX_LOG_LENGTH) {
                        receivedText.delete(0, receivedText.length() - TRIM_THRESHOLD);
                        // 确保从完整行开始
                        int firstNewline = receivedText.indexOf("\n");
                        if (firstNewline > 0) {
                            receivedText.delete(0, firstNewline + 1);
                        }
                    }

                    // 追加新文本
                    receivedText.append(text).append(LINE_BREAK);

                    // 仅在有实际变化时更新TextView
                    if (receivedText.length() - lastTextLength > 100 ||
                            System.currentTimeMillis() - lastUpdateTime > 500) {
                        tvReceived.setText(receivedText.toString());
                        lastTextLength = receivedText.length();
                        lastUpdateTime = System.currentTimeMillis();

                        // 滚动到底部
                        ((ScrollView) tvReceived.getParent()).fullScroll(View.FOCUS_DOWN);
                    }
                }
            });
        } catch (Exception e) {
            Log.e("USB", "解码失败", e);
        }
    }

//    double sppedMax = 0;
//    private String formatSpeed(double speedKB) {
////        if (sppedMax == 0){
////            sppedMax  = speedKB;
////        }
////        sppedMax  = (speedKB+ sppedMax)/2;
////        speedKB = sppedMax;
//        if (speedKB < 1) {
//            return String.format(Locale.getDefault(), "%.2f B/s", speedKB * 1024);
//        } else if (speedKB < 1024) {
//            return String.format(Locale.getDefault(), "%.2f KB/s", speedKB);
//        } else {
//            return String.format(Locale.getDefault(), "%.2f MB/s", speedKB / 1024);
//        }
//    }

    private static final List<Double> speedHistory1 = new ArrayList<>();

    private String formatSpeed(double speedKB) {
        // 添加当前瞬时值到历史记录
        speedHistory1.add(speedKB);

        // 计算平均值
        double sum = 0;
        for (double speed : speedHistory1) {
            sum += speed;
        }
        double avgSpeedKB = sum / speedHistory1.size();

        // 格式化输出
        if (avgSpeedKB < 1) {
            return String.format(Locale.getDefault(), "%.2f B/s (avg)", avgSpeedKB * 1024);
        } else if (avgSpeedKB < 1024) {
            return String.format(Locale.getDefault(), "%.2f KB/s (avg)", avgSpeedKB);
        } else {
            return String.format(Locale.getDefault(), "%.2f MB/s (avg)", avgSpeedKB / 1024);
        }
    }

    private void processReceivedDataImage(byte[] chunk, int length) {
        if (!isReceivingImage && length >= 4) {
            expectedImageSize = ByteBuffer.wrap(chunk, 0, 4).getInt();
            imageBuffer.write(chunk, 4, length - 4);
            isReceivingImage = true;
            Log.d("USB", "开始接收图片，预期大小: " + expectedImageSize);
            return;
        }

        if (isReceivingImage) {
            imageBuffer.write(chunk, 0, length);
            runOnUiThread(() -> {
                int progress = (int) (imageBuffer.size() * 100.0 / expectedImageSize);
                tvProgress.setText("接收进度: " + progress + "%");
            });

            if (imageBuffer.size() >= expectedImageSize) {
                showReceivedImage(imageBuffer.toByteArray());
                imageBuffer.reset();
                isReceivingImage = false;
            }
        }
    }

    private void showReceivedImage(byte[] imageData) {
        runOnUiThread(() -> {
            try {
                Bitmap bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
                if (bitmap != null) {
                    ivReceivedImage.setImageBitmap(bitmap);
                    tvProgress.setText("图片显示完成");
                } else {
                    tvProgress.setText("图片解析失败");
                }
            } catch (Exception e) {
                Log.e("USB", "图片显示错误", e);
                tvProgress.setText("显示错误: " + e.getMessage());
            }
        });
    }

    private void loadAudioFile() {
        try {
            InputStream is = getAssets().open("test3MB.mp3");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] temp = new byte[1024];
            int read;
            while ((read = is.read(temp)) != -1) {
                buffer.write(temp, 0, read);
            }
            audioData = buffer.toByteArray();
            Log.d("Audio", "音频加载完成，大小: " + audioData.length + "字节");
        } catch (IOException e) {
            Log.e("Audio", "加载音频失败", e);
        }
    }

    private void sendAudioData() {
        try {
            runOnUiThread(() -> {
                progressAudioSend.setMax(audioData.length);
                tvAudioSendStatus.setText("开始发送...");
            });

            byte[] header = ByteBuffer.allocate(5)
                    .put(AUDIO_FLAG)
                    .putInt(audioData.length)
                    .array();
            serialPort.write(header, 1000);

            int chunkSize = 512;
            for (int i = 0; i < audioData.length; i += chunkSize) {
                byte[] chunk = Arrays.copyOfRange(audioData, i, Math.min(i + chunkSize, audioData.length));
                byte[] packet = new byte[chunk.length + 1];
                packet[0] = AUDIO_FLAG;
                System.arraycopy(chunk, 0, packet, 1, chunk.length);
                serialPort.write(packet, 1000);

                if (i % (chunkSize * 5) == 0) {
                    final int progress = i;
                    runOnUiThread(() -> {
                        progressAudioSend.setProgress(progress);
                        tvAudioSendStatus.setText("已发送: " + progress + "/" + audioData.length);
                    });
                }
            }

            runOnUiThread(() -> {
                progressAudioSend.setProgress(audioData.length);
                tvAudioSendStatus.setText("发送完成");
            });
        } catch (IOException e) {
            runOnUiThread(() -> tvAudioSendStatus.setText("发送错误: " + e.getMessage()));
        }
    }

    @SuppressLint("DefaultLocale")
    private void processReceivedDataAudio(byte[] data, int length) {
        if (!isReceivingAudio && length >= 4) {
            expectedAudioSize = ByteBuffer.wrap(data, 0, 4).getInt();
            audioBuffer.write(data, 4, length - 4);
            isReceivingAudio = true;
            runOnUiThread(() -> {
                progressAudioReceive.setMax(expectedAudioSize);
                tvAudioStatus.setText("正在接收音频...");
            });
            return;
        }

        if (isReceivingAudio) {
            audioBuffer.write(data, 0, length);
            runOnUiThread(() -> {
                progressAudioReceive.setProgress(audioBuffer.size());
                tvAudioStatus.setText(String.format(
                        "已接收: %d/%d字节",
                        audioBuffer.size(),
                        expectedAudioSize
                ));
            });

            if (audioBuffer.size() >= expectedAudioSize) {
                receivedAudioData = audioBuffer.toByteArray();
                runOnUiThread(() -> {
                    btnPlay.setEnabled(true);
                    tvAudioStatus.setText("音频接收完成，点击播放");
                });
                audioBuffer.reset();
                isReceivingAudio = false;
            }
        }
    }

    private void playAudio() {
        if (receivedAudioData == null) return;

        try {
            if (mediaPlayer != null) {
                mediaPlayer.release();
            }

            File tempFile = File.createTempFile("temp_audio", ".mp3", getCacheDir());
            FileOutputStream fos = new FileOutputStream(tempFile);
            fos.write(receivedAudioData);
            fos.close();

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(tempFile.getAbsolutePath());
            mediaPlayer.prepare();
            mediaPlayer.start();

            mediaPlayer.setOnCompletionListener(mp -> {
                mp.release();
                tempFile.delete();
                mediaPlayer = null;
            });
        } catch (IOException e) {
            Log.e("Audio", "播放失败", e);
            tvAudioStatus.setText("播放失败: " + e.getMessage());
        }
    }

    private boolean findUsbDevice() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        for (UsbDevice device : deviceList.values()) {
            Log.d("USB", "找到设备: VendorId=" + device.getVendorId() + ", ProductId=" + device.getProductId());
            requestUsbPermission(device);
            return true;
        }
        return false;
    }

    private void requestUsbPermission(UsbDevice device) {
        PendingIntent permissionIntent = PendingIntent.getBroadcast(
                this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE);
        usbManager.requestPermission(device, permissionIntent);
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (sendHandler != null && sendRunnable != null) {
            sendHandler.removeCallbacks(sendRunnable);
        }
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (imageBuffer != null) {
            try {
                imageBuffer.close();
            } catch (IOException e) {
                Log.e("Resource", "关闭imageBuffer失败", e);
            }
        }
        super.onDestroy();
        try {
            if (serialPort != null) {
                serialPort.close();
            }
        } catch (IOException e) {
            Log.e("USB", "关闭失败", e);
        }
    }
}