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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    private static final String ACTION_USB_PERMISSION = "com.example.USB_PERMISSION";
    private UsbManager usbManager;
    private UsbSerialPort serialPort;
    private TextView tvReceived;
    private TextView tvDeviceInfo;
    private Button btnSend;
    private Button btnSendOnce;
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
    private static final byte RETRANSMIT_FLAG = (byte) 0xFF;

    private Handler sendHandler = new Handler(Looper.getMainLooper());
    private Runnable sendRunnable;
    private int sendCount = 0;
    private int sequenceNumber = 0; // 序列号，0-65535
    private HashMap<Integer, byte[]> retransmitCache = new HashMap<>(); // 缓存待重传数据
    private ByteArrayOutputStream textBuffer = new ByteArrayOutputStream(); // 文本接收缓冲区
    private boolean isReceivingText = false;
    private int expectedTextSize = 0;
    private final AtomicBoolean isRetransmitting = new AtomicBoolean(false); // 控制重传状态
    private static final int WINDOW_SIZE = 5; // 滑动窗口大小（帧数）
    private static final int MAX_PAYLOAD = 243; // 最大 Payload，适配 P900
    private static final int FRAME_INTERVAL_MS = 50; // 帧间间隔

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

        // 加载固定图片和音频
        loadFixedImage();
        loadAudioFile();

        // USB设备检测
        findUsbDevice();

        btnPlay.setOnClickListener(v -> playAudio());

        // 定时发送按钮（发送 480 字节 "1"）
        btnSend.setOnClickListener(v -> {
            if (sendRunnable != null) {
                sendHandler.removeCallbacks(sendRunnable);
                sendRunnable = null;
                Toast.makeText(this, "已停止发送", Toast.LENGTH_SHORT).show();
                btnSend.setText("发送");
            } else {
                sendCount = 0;
                receivedText.setLength(0);
                tvReceived.setText("");

                sendRunnable = new Runnable() {
                    @Override
                    public void run() {
                        byte[] dataToSend = new byte[480];
                        Arrays.fill(dataToSend, (byte) '1');
                        String dataString = new String(dataToSend, StandardCharsets.UTF_8);
                        sendTextData(dataString);
                        sendCount++;
                        runOnUiThread(() -> {
                            receivedText.append("发送次数: ").append(sendCount).append(LINE_BREAK);
                            tvReceived.setText(receivedText.toString());
                            ((ScrollView) tvReceived.getParent()).fullScroll(View.FOCUS_DOWN);
                        });
                        sendHandler.postDelayed(this, 100);
                    }
                };
                sendHandler.post(sendRunnable);
                Toast.makeText(this, "开始定时发送", Toast.LENGTH_SHORT).show();
                btnSend.setText("停止");
            }
        });

        // 单次发送按钮
        btnSendOnce.setOnClickListener(v -> {
            String inputText = etInput.getText().toString().trim();
            if (!inputText.isEmpty()) {
                sendTextData(inputText);
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
            InputStream is = getAssets().open("b.png ");
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

    private synchronized int getNextSequenceNumber() {
        int seq = sequenceNumber;
        sequenceNumber = (sequenceNumber + 1) % 65536; // 0-65535 循环
        return seq;
    }

    private void sendDataWithSlidingWindow(byte[] data, byte flag, TextView progressTextView, ProgressBar progressBar) {
        if (serialPort == null) {
            runOnUiThread(() -> {
                if (flag == IMAGE_FLAG) {
                    tvProgress.setText("USB设备未连接");
                } else if (flag == AUDIO_FLAG) {
                    tvAudioSendStatus.setText("USB设备未连接");
                }
            });
            return;
        }

        int seqNum = getNextSequenceNumber();
        retransmitCache.put(seqNum, data); // 缓存数据以支持重传

        new Thread(() -> {
            try {
                int chunkSize = MAX_PAYLOAD;
                int totalFrames = (int) Math.ceil((double) data.length / chunkSize);
                int totalWindows = (int) Math.ceil((double) totalFrames / WINDOW_SIZE);

                for (int window = 0; window < totalWindows && !isRetransmitting.get(); window++) {
                    int startFrame = window * WINDOW_SIZE;
                    int endFrame = Math.min(startFrame + WINDOW_SIZE, totalFrames);

                    // 发送当前窗口的帧
                    for (int i = startFrame; i < endFrame; i++) {
                        sleep(500);
                        int start = i * chunkSize;
                        int end = Math.min(start + chunkSize, data.length);
                        byte[] chunk = Arrays.copyOfRange(data, start, end);

                        ByteBuffer packet = ByteBuffer.allocate(7 + chunk.length);
                        packet.put(flag); // Flag
                        packet.putInt(data.length); // Total Length
                        packet.putShort((short) seqNum); // Sequence Number
                        packet.put(chunk); // Payload

                        serialPort.write(packet.array(), 1000);
                        Log.d("USB", "发送帧: 序列号 " + seqNum + ", 帧 " + i + ", 长度 " + chunk.length);
                        sleep(FRAME_INTERVAL_MS); // 帧间间隔
                    }
                    sleep(500);

                    // 更新进度
                    if (progressTextView != null && progressBar != null) {
                        final int progress = (endFrame * chunkSize * 100) / data.length;
                        runOnUiThread(() -> {
                            progressBar.setProgress(Math.min(endFrame * chunkSize, data.length));
                            progressTextView.setText(flag == IMAGE_FLAG ?
                                    "发送进度: " + progress + "%" :
                                    "已发送: " + Math.min(endFrame * chunkSize, data.length) + "/" + data.length);
                        });
                    }
                }

                if (!isRetransmitting.get()) {
                    Log.d("USB", "数据发送完成: 序列号 " + seqNum + ", 长度 " + data.length);
                    if (flag == IMAGE_FLAG) {
                        runOnUiThread(() -> tvProgress.setText("图片发送完成"));
                    } else if (flag == AUDIO_FLAG) {
                        runOnUiThread(() -> tvAudioSendStatus.setText("发送完成"));
                    }
                }
            } catch (IOException | InterruptedException e) {
                Log.e("USB", "发送失败", e);
                runOnUiThread(() -> {
                    if (flag == IMAGE_FLAG) {
                        tvProgress.setText("发送失败: " + e.getMessage());
                    } else if (flag == AUDIO_FLAG) {
                        tvAudioSendStatus.setText("发送错误: " + e.getMessage());
                    }
                });
            }
        }).start();
    }

    private void sendTextData(String data) {
        byte[] textBytes = data.getBytes(StandardCharsets.UTF_8);
        sendDataWithSlidingWindow(textBytes, TEXT_FLAG, null, null);
    }

    private void sendImageData(byte[] imageData) {
        sendDataWithSlidingWindow(imageData, IMAGE_FLAG, tvProgress, progressAudioSend);
    }

    private void sendAudioData() {
        if (audioData == null) {
            runOnUiThread(() -> tvAudioSendStatus.setText("音频未加载"));
            return;
        }
        runOnUiThread(() -> progressAudioSend.setMax(audioData.length));
        sendDataWithSlidingWindow(audioData, AUDIO_FLAG, tvAudioSendStatus, progressAudioSend);
    }

    private void requestRetransmit(int seqNum) {
        try {
            ByteBuffer packet = ByteBuffer.allocate(3);
            packet.put(RETRANSMIT_FLAG);
            packet.putShort((short) seqNum);
            serialPort.write(packet.array(), 1000);
            Log.d("USB", "请求重传: 序列号 " + seqNum);
        } catch (IOException e) {
            Log.e("USB", "重传请求失败", e);
        }
    }

    private void retransmitData(int seqNum) {
        byte[] data = retransmitCache.get(seqNum);
        if (data == null) {
            Log.e("USB", "重传数据不存在: 序列号 " + seqNum);
            return;
        }

        isRetransmitting.set(true);
        if (data.length > 0 && data[0] == '1') { // 假设文本数据以 "1" 开头
            sendTextData(new String(data, StandardCharsets.UTF_8));
        } else if (fixedImageBytes != null && Arrays.equals(data, fixedImageBytes)) {
            sendImageData(data);
        } else if (audioData != null && Arrays.equals(data, audioData)) {
            new Thread(() -> sendAudioData()).start();
        }
        Log.d("USB", "重传数据: 序列号 " + seqNum + ", 长度 " + data.length);
        isRetransmitting.set(false);
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
                tvDeviceInfo.setText("设备已拔出");
                Log.d("USB", "设备拔出");
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

    private void startReading() {
        new Thread(() -> {
            byte[] buffer = new byte[1024];
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    int len = serialPort.read(buffer, 1000);
                    if (len > 7) { // 最小帧：Flag(1)+Total Length(4)+Seq Num(2)
                        byte flag = buffer[0];
                        if (flag == RETRANSMIT_FLAG) {
                            int seqNum = ByteBuffer.wrap(buffer, 1, 2).getShort();
                            retransmitData(seqNum);
                        } else {
                            int totalLength = ByteBuffer.wrap(buffer, 1, 4).getInt();
                            int seqNum = ByteBuffer.wrap(buffer, 5, 2).getShort();
                            byte[] data = Arrays.copyOfRange(buffer, 7, len);
                            if (flag == TEXT_FLAG) {
                                processReceiveDataText(data, totalLength, seqNum);
                            } else if (flag == IMAGE_FLAG) {
                                processReceivedDataImage(data, totalLength, seqNum);
                            } else if (flag == AUDIO_FLAG) {
                                processReceivedDataAudio(data, totalLength, seqNum);
                            }
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

    private void processReceiveDataText(byte[] receivedBytes, int expectedSize, int seqNum) {
        try {
            long currentTime = System.currentTimeMillis();
            int currentByteCount = receivedBytes.length;

            if (lastReceiveTime > 0) {
                double intervalSec = (currentTime - lastReceiveTime) / 1000.0;
                currentSpeed = currentByteCount / intervalSec / 1024;
                speedHistory[speedIndex % 5] = currentSpeed;
                speedIndex++;
                currentSpeed = Arrays.stream(speedHistory).average().orElse(0);
            }
            lastReceiveTime = currentTime;

            if (!isReceivingText) {
                textBuffer.reset();
                expectedTextSize = expectedSize;
                isReceivingText = true;
            }

            textBuffer.write(receivedBytes);
            runOnUiThread(() -> tvSpeed.setText(formatSpeed(currentSpeed)));

            if (textBuffer.size() >= expectedTextSize) {
                if (textBuffer.size() == expectedTextSize) {
                    String text = new String(textBuffer.toByteArray(), StandardCharsets.UTF_8);
                    runOnUiThread(() -> {
                        receivedText.append("收到文本: ").append(text).append(LINE_BREAK);
                        tvReceived.setText(receivedText.toString());
                        ((ScrollView) tvReceived.getParent()).fullScroll(View.FOCUS_DOWN);
                    });
                    retransmitCache.remove(seqNum); // 成功接收，移除缓存
                } else {
                    Log.e("USB", "文本长度不匹配: 预期 " + expectedTextSize + ", 实际 " + textBuffer.size());
                    requestRetransmit(seqNum);
                }
                textBuffer.reset();
                isReceivingText = false;
            }
        } catch (Exception e) {
            Log.e("USB", "文本解码失败", e);
            requestRetransmit(seqNum);
            textBuffer.reset();
            isReceivingText = false;
        }
    }

    private void processReceivedDataImage(byte[] chunk, int expectedSize, int seqNum) {
        try {
            if (!isReceivingImage) {
                imageBuffer.reset();
                expectedImageSize = expectedSize;
                isReceivingImage = true;
            }

            imageBuffer.write(chunk);
            runOnUiThread(() -> {
                int progress = (int) (imageBuffer.size() * 100.0 / expectedImageSize);
                tvProgress.setText("接收进度: " + progress + "%");
            });

            if (imageBuffer.size() >= expectedImageSize) {
                if (imageBuffer.size() == expectedImageSize) {
                    showReceivedImage(imageBuffer.toByteArray());
                    retransmitCache.remove(seqNum);
                } else {
                    Log.e("USB", "图片长度不匹配: 预期 " + expectedImageSize + ", 实际 " + imageBuffer.size());
                    requestRetransmit(seqNum);
                }
                imageBuffer.reset();
                isReceivingImage = false;
            }
        } catch (Exception e) {
            Log.e("USB", "图片接收失败", e);
            requestRetransmit(seqNum);
            imageBuffer.reset();
            isReceivingImage = false;
        }
    }

    private void processReceivedDataAudio(byte[] chunk, int expectedSize, int seqNum) {
        try {
            if (!isReceivingAudio) {
                audioBuffer.reset();
                expectedAudioSize = expectedSize;
                isReceivingAudio = true;
                runOnUiThread(() -> {
                    progressAudioReceive.setMax(expectedAudioSize);
                    tvAudioStatus.setText("正在接收音频...");
                });
            }

            audioBuffer.write(chunk);
            runOnUiThread(() -> {
                progressAudioReceive.setProgress(audioBuffer.size());
                tvAudioStatus.setText(String.format(
                        "已接收: %d/%d字节",
                        audioBuffer.size(),
                        expectedAudioSize
                ));
            });

            if (audioBuffer.size() >= expectedAudioSize) {
                if (audioBuffer.size() == expectedAudioSize) {
                    receivedAudioData = audioBuffer.toByteArray();
                    runOnUiThread(() -> {
                        btnPlay.setEnabled(true);
                        tvAudioStatus.setText("音频接收完成，点击播放");
                    });
                    retransmitCache.remove(seqNum);
                } else {
                    Log.e("USB", "音频长度不匹配: 预期 " + expectedAudioSize + ", 实际 " + audioBuffer.size());
                    requestRetransmit(seqNum);
                }
                audioBuffer.reset();
                isReceivingAudio = false;
            }
        } catch (Exception e) {
            Log.e("USB", "音频接收失败", e);
            requestRetransmit(seqNum);
            audioBuffer.reset();
            isReceivingAudio = false;
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

    private String formatSpeed(double speedKB) {
        if (speedKB < 1) {
            return String.format(Locale.getDefault(), "%.2f B/s", speedKB * 1024);
        } else if (speedKB < 1024) {
            return String.format(Locale.getDefault(), "%.2f KB/s", speedKB);
        } else {
            return String.format(Locale.getDefault(), "%.2f MB/s", speedKB / 1024);
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
        if (textBuffer != null) {
            try {
                textBuffer.close();
            } catch (IOException e) {
                Log.e("Resource", "关闭textBuffer失败", e);
            }
        }
        if (audioBuffer != null) {
            try {
                audioBuffer.close();
            } catch (IOException e) {
                Log.e("Resource", "关闭audioBuffer失败", e);
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
        unregisterReceiver(usbReceiver);
    }
}