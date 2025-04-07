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


public class MainActivity extends AppCompatActivity {
    private static final String ACTION_USB_PERMISSION = "com.example.USB_PERMISSION";
    private UsbManager usbManager;
    private UsbSerialPort serialPort;
    private TextView tvReceived;

    private TextView tvDeviceInfo;
    private Button btnSend;
    private Button btnSendImage;

    private TextView tvProgress;
    private byte[] fixedImageBytes;
    private EditText etInput;
    private TextView tvSpeed;

    private ImageView ivReceivedImage;  // 图片显示控件
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
        // 发送文字
        btnSend = findViewById(R.id.btn_send);
        // 新增进度显示控件
        tvProgress = findViewById(R.id.tv_progress);

        ivReceivedImage = findViewById(R.id.iv_received_image);

        etInput = findViewById(R.id.et_input);
        // 发送音频
        btnSendAudio = findViewById(R.id.btn_send_audio);

        progressAudioReceive = findViewById(R.id.progress_audio_receive);
        tvAudioStatus = findViewById(R.id.tv_audio_status);
        btnPlay = findViewById(R.id.btn_play);
        // 发送音频
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

//        btnSend.setOnClickListener(v -> sendData("hello"));

        // 发送文字
        btnSend.setOnClickListener(v -> {
            // 获取输入内容并去除首尾空格
            String inputText = etInput.getText().toString().trim();

            // 检查输入是否为空
//            if (inputText.isEmpty()) {
//                Toast.makeText(this, "输入不能为空", Toast.LENGTH_SHORT).show();
//                return;
//            }

            // 发送动态内容
//            sendTextData(inputText);

            while (true){
                sendTextData(inputText);
            }

            // 清空输入框（可选）
//            etInput.setText("");
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
            // 1. 发送开始时更新状态
            runOnUiThread(() -> {
                tvAudioSendStatus.setText("发送中...");
                progressAudioSend.setProgress(0);
            });

            // 2. 启动发送线程
            new Thread(() -> {
                sendAudioData(); // 内部需分片更新进度
            }).start();
        });


        // 注册广播接收器
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        registerReceiver(usbReceiver, filter);
    }

    // 从assets加载固定图片
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

    // 图片发送
    private void sendImageData(byte[] imageData) {
        if (serialPort == null) {
            Toast.makeText(this, "USB设备未连接", Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            try {
                // 双重检查serialPort状态
                if (serialPort == null || !serialPort.isOpen()) {
                    runOnUiThread(() ->
                            Toast.makeText(this, "USB未连接", Toast.LENGTH_SHORT).show());
                    return;
                }

                // 检查图片数据有效性
                if (imageData == null || imageData.length == 0) {
                    runOnUiThread(() ->
                            Toast.makeText(this, "图片数据无效", Toast.LENGTH_SHORT).show());
                    return;
                }

                // 发送图片大小信息（4字节）
//                byte[] sizeInfo = ByteBuffer.allocate(4).putInt(imageData.length).array();
                byte[] sizeInfo = ByteBuffer.allocate(5)
                        .put(IMAGE_FLAG)          // 1字节标记位
                        .putInt(imageData.length) // 4字节图片大小
                        .array();
                serialPort.write(sizeInfo, 1000);

                // 分片发送图片数据
                int chunkSize = 63;  // 每包512字节
                int totalPackets = (int) Math.ceil((double)imageData.length / chunkSize);

                for (int i = 0; i < totalPackets; i++) {
                    sleep(3000);

                    int start = i * chunkSize;
                    int end = Math.min(start + chunkSize, imageData.length);
                    byte[] chunk = Arrays.copyOfRange(imageData, start, end);

                    if (chunk.length == 0) {
                        continue;
                    }

                    byte[] packet = new byte[chunk.length + 1];
                    packet[0] = IMAGE_FLAG;
                    if(i == totalPackets-1){
                        packet[0] = IMAGE_FLAG_END;
                    }

                    System.arraycopy(chunk, 0, packet, 1, chunk.length);
                    serialPort.write(packet, 1000);

//                    serialPort.write(chunk, 1000);

                    // 更新进度
                    final int progress = (i + 1) * 100 / totalPackets;
                    runOnUiThread(() -> tvProgress.setText("发送进度: " + progress + "%"));
                }

                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "图片发送完成", Toast.LENGTH_SHORT).show());

            } catch (IOException e) {
                Log.e("USB", "图片发送失败", e);
                runOnUiThread(() -> tvProgress.setText("发送失败: " + e.getMessage()));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
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
                    // 更新界面，显示设备信息
                    String deviceInfo = "设备插入: " + "VendorId=" + device.getVendorId() +
                            ", ProductId=" + device.getProductId();
                    tvDeviceInfo.setText(deviceInfo);
                    Log.d("USB", "设备插入: " + deviceInfo);
                    requestUsbPermission(device); // 请求权限
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    // 更新界面，显示设备拔出信息
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
//                serialPort.write(data.getBytes(StandardCharsets.UTF_8), 1000);

                byte[] textBytes = data.getBytes(StandardCharsets.UTF_8);
                Arrays.fill(textBytes, (byte) 0x41);
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

//    private void startReading() {
//        new Thread(() -> {
//            byte[] buffer = new byte[64];
//            while (true) {
//                try {
//                    int len = serialPort.read(buffer, 1000);
//                    if (len > 0) {
//                        String receivedData = new String(buffer, 0, len);
//                        runOnUiThread(() -> tvReceived.setText("接收到: " + receivedData));
//                        Log.d("USB", "接收到数据: " + receivedData);
//                    }
//                } catch (IOException e) {
//                    Log.e("USB", "读取失败", e);
//                    break;
//                }
//            }
//        }).start();
//    }

    private void startReading() {
//        // 音频
//        new Thread(() -> {
//            byte[] buffer = new byte[1024]; // 接收缓冲区
//            while (!Thread.currentThread().isInterrupted()) {
//                try {
//                    // 从串口读取数据
//                    int len = serialPort.read(buffer, 1000); // 超时1秒
//                    if (len > 0) {
//                        // 关键调用点：将收到的字节交给处理器
//                        processReceivedDataAudio(buffer, len);
//                    }
//                } catch (IOException e) {
//                    Log.e("USB", "接收中断", e);
//                    break;
//                }
//            }
//        }).start();
//
//        // 图片
//        new Thread(() -> {
//            byte[] buffer = new byte[1024];
//            while (!Thread.currentThread().isInterrupted()) {
//                try {
//                    int len = serialPort.read(buffer, 1000);
//                    if (len > 0) {
//                        processReceivedDataImage(buffer, len);
//                    }
//                } catch (IOException e) {
//                    Log.e("USB", "接收中断", e);
//                    break;
//                }
//            }
//        }).start();
//
////        // 文字 单行
////        new Thread(() -> {
////            byte[] buffer = new byte[64];
////            while (true) {
////                try {
////                    int len = serialPort.read(buffer, 1000);
////                    if (len > 0) {
////                        String receivedData = new String(buffer, 0, len);
////                        runOnUiThread(() -> tvReceived.setText("接收到: " + receivedData));
////                        Log.d("USB", "接收到数据: " + receivedData);
////                    }
////                } catch (IOException e) {
////                    Log.e("USB", "读取失败", e);
////                    break;
////                }
////            }
////        }).start();
//
//        // 文字 多行
//        new Thread(() -> {
//            byte[] buffer = new byte[1024];
//            while (!Thread.currentThread().isInterrupted()) {
//                try {
//                    int len = serialPort.read(buffer, 1000);
//                    if (len > 0) {
//                        String newData = new String(buffer, 0, len, StandardCharsets.UTF_8);
//                        appendReceivedData(newData); // 追加新数据
//                    }
//                } catch (IOException e) {
//                    Log.e("USB", "接收中断", e);
//                    break;
//                }
//            }
//        }).start();


        new Thread(() -> {
            byte[] buffer = new byte[1024];
            byte[] dataTemp = new byte[64];
            boolean flag64 = false;
            int dataLenTemp = 0;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    int len = serialPort.read(buffer, 1000);
                    if (len > 1) {
                        byte flag = buffer[0];
                        byte[] data = Arrays.copyOfRange(buffer, 1, len);
//                        String newData = new String(buffer, 1, len - 1, StandardCharsets.UTF_8);

//                        if((len == 5 && flag == IMAGE_FLAG)||flag == IMAGE_FLAG_END){
//                            processReceivedDataImage(data, len-1);
//                            continue;
//                        }
//
//                        if(data.length < 63 && !flag64){
//                            System.arraycopy(data, 0, dataTemp, 0, data.length);
//                            flag64 = true;
//                            dataLenTemp = data.length;
//                            continue;
//                        }
//
//                        if(data.length < 63 && flag64){
//                            System.arraycopy(data, 0, dataTemp, dataLenTemp, data.length);
//                            flag64 = false;
//                            data = dataTemp;
//                        }

                        if (flag == TEXT_FLAG) {
                            processReceiveDataText(data); // 处理文本数据
                        } else if(flag == IMAGE_FLAG){
                            processReceivedDataImage(data, len-1);
                        }else {
                            processReceivedDataAudio(data, len-1);
                        }
                    }
                } catch (IOException e) {
                    Log.e("USB", "接收中断", e);
                    break;
                }
            }
        }).start();
    }

//    private void processReceiveDataText(byte[] receivedBytes) {
//        try {
//            String text = new String(receivedBytes, StandardCharsets.UTF_8);
//
//            runOnUiThread(() -> {
//                receivedText.append(text).append(LINE_BREAK);
//                tvReceived.setText(receivedText.toString());
//                // 滚动到底部
//                ((ScrollView) tvReceived.getParent()).fullScroll(View.FOCUS_DOWN);
//            });
//        } catch (Exception e) {
//            Log.e("USB", "解码失败", e);
//        }
//    }

    // 成员变量
    private long lastReceiveTime = 0;
    private double currentSpeed = 0;
    private double[] speedHistory = new double[5];
    private int speedIndex = 0;

    private void processReceiveDataText(byte[] receivedBytes) {
        try {
            long currentTime = System.currentTimeMillis();
            int currentByteCount = receivedBytes.length;

            if (lastReceiveTime > 0) {
                double intervalSec = (currentTime - lastReceiveTime) / 1000.0;
                currentSpeed = currentByteCount / intervalSec / 1024;

                // 平滑处理
                speedHistory[speedIndex % 5] = currentSpeed;
                speedIndex++;
                currentSpeed = Arrays.stream(speedHistory).average().orElse(0);
            }

            lastReceiveTime = currentTime;

            String text = new String(receivedBytes, StandardCharsets.UTF_8);
            runOnUiThread(() -> {
                tvSpeed.setText(formatSpeed(currentSpeed));
                receivedText.append(text).append(LINE_BREAK);
                tvReceived.setText(receivedText.toString());
                ((ScrollView) tvReceived.getParent()).fullScroll(View.FOCUS_DOWN);
            });

        } catch (Exception e) {
            Log.e("USB", "解码失败", e);
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

    private void processReceivedDataImage(byte[] chunk, int length) {
        // 协议：前4字节是图片大小（大端序）
        if (!isReceivingImage && length >= 4) {
            expectedImageSize = ByteBuffer.wrap(chunk, 0, 4).getInt();
            imageBuffer.write(chunk, 4, length - 4);  // 写入剩余数据
            isReceivingImage = true;
            Log.d("USB", "开始接收图片，预期大小: " + expectedImageSize);
            return;
        }


        // 正在接收图片数据
        if (isReceivingImage) {
//            long timeStart = System.currentTimeMillis();
            final int[] imageBufferAgo = {0};

            imageBuffer.write(chunk, 0, length);

            // 更新进度
            runOnUiThread(() -> {
                int progress = (int) (imageBuffer.size() * 100.0 / expectedImageSize);
//                long timeStop = System.currentTimeMillis();
//                int speed = (int) ((imageBuffer.size()- imageBufferAgo[0])/(timeStop - timeStart));
//                tvProgress.setText("接收进度: " + progress + "%   " + speed + "kbps");
                tvProgress.setText("接收进度: " + progress + "%   ");
                imageBufferAgo[0] = imageBuffer.size();
            });

            // 接收完成
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
        //loadAudioFile();
        try {
            // 1. 初始化进度
            runOnUiThread(() -> {
                progressAudioSend.setMax(audioData.length);
                tvAudioSendStatus.setText("开始发送...");
            });

            // 2. 发送文件头
//            byte[] header = ByteBuffer.allocate(4).putInt(audioData.length).array();
//            byte[] header = ByteBuffer.allocate(5).putInt(AUDIO_FLAG+audioData.length).array();
//            serialPort.write(header, 1000);

            byte[] header = ByteBuffer.allocate(5)
                    .put(AUDIO_FLAG)          // 1字节标记位
                    .putInt(audioData.length) // 4字节图片大小
                    .array();
            serialPort.write(header, 1000);

            // 3. 分片发送
            int chunkSize = 512;
            for (int i = 0; i < audioData.length; i += chunkSize) {
                byte[] chunk = Arrays.copyOfRange(audioData, i, Math.min(i + chunkSize, audioData.length));

                byte[] packet = new byte[chunk.length + 1];
                packet[0] = AUDIO_FLAG;
                System.arraycopy(chunk, 0, packet, 1, chunk.length);
                serialPort.write(packet, 1000);

//                serialPort.write(chunk, 1000);

                // 4. 实时更新进度（限制刷新频率）
                if (i % (chunkSize * 5) == 0) { // 每发送5个块更新一次UI
                    final int progress = i;
                    runOnUiThread(() -> {
                        progressAudioSend.setProgress(progress);
                        tvAudioSendStatus.setText("已发送: " + progress + "/" + audioData.length);
                    });
                }
            }

            // 5. 最终完成状态
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
        // 音频协议：前4字节是文件大小
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

//        long timeStart = System.currentTimeMillis();
//        final int[] imageBufferAgo = {0};
//
//        imageBuffer.write(chunk, 0, length);
//
//        // 更新进度
//        runOnUiThread(() -> {
//            int progress = (int) (imageBuffer.size() * 100.0 / expectedImageSize);
//            long timeStop = System.currentTimeMillis();
//            int speed = (int) ((imageBuffer.size()- imageBufferAgo[0])/(timeStop - timeStart));
//            tvProgress.setText("接收进度: " + progress + "%   " + speed + "bps");
//            imageBufferAgo[0] = imageBuffer.size();
//        });


        if (isReceivingAudio) {
//            long timeStart = System.currentTimeMillis();
            final int[] imageBufferAgo = {0};

            audioBuffer.write(data, 0, length);

            // 更新进度
            runOnUiThread(() -> {
//                long timeStop = System.currentTimeMillis();
//                int speed = (int) ((audioBuffer.size()- imageBufferAgo[0])/(timeStop - timeStart));
                progressAudioReceive.setProgress(audioBuffer.size());
                tvAudioStatus.setText(String.format(
                        "已接收: %d/%d字节",
                        audioBuffer.size(),
                        expectedAudioSize
                ));

//                tvAudioStatus.setText(speed+"kbps");
                imageBufferAgo[0] = audioBuffer.size();
            });

            // 接收完成
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
            // 释放之前的播放器
            if (mediaPlayer != null) {
                mediaPlayer.release();
            }

            // 创建临时文件（仅内存缓存）
            File tempFile = File.createTempFile("temp_audio", ".mp3", getCacheDir());
            FileOutputStream fos = new FileOutputStream(tempFile);
            fos.write(receivedAudioData);
            fos.close();

            // 初始化播放器
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(tempFile.getAbsolutePath());
            mediaPlayer.prepare();
            mediaPlayer.start();

            // 播放完成自动清理
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

    private void showToast(String message){
        Toast.makeText(this,message,Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
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