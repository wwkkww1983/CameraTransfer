package com.slin.camera_transfer.transfer;

import android.media.Image;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.slin.camera_transfer.model.ImageFrame;
import com.slin.camera_transfer.transfer.task.ImageFrameTask;
import com.slin.camera_transfer.transfer.task.ImageFrameTaskImpl;
import com.slin.camera_transfer.transfer.writer.ImageFrameWriter;
import com.slin.camera_transfer.transfer.writer.ImageFrameWriterImpl;
import com.slin.camera_transfer.utils.LogUtils;
import com.slin.camera_transfer.utils.Utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * author: slin
 * date: 2019-06-27
 * description:
 */
public class ImageTransfer {

    private Socket socket;
    private OutputStream outputStream;
    private InputStream inputStream;

    private String mServerIp;
    private int mPort;
    private boolean isConnected = false;

    //图片传输线程
    private HandlerThread transferThread;
    private Handler transferHandler;
    private Handler mainThreadHandler;
    //记录任务数
    private AtomicInteger taskSize = new AtomicInteger(0);

    private boolean isConnecting = false;

    private OnTransferListener transferListener;
    private OnConnectListener connectListener;

    private Runnable connectRunnable = this::connectInternal;

    public static ImageTransfer getDefault() {
        return new ImageTransfer("192.168.1.102", 4040);
    }

    public ImageTransfer(String serverIp, int port) {
        this.mServerIp = serverIp;
        this.mPort = port;
        initTransferThread();
    }

    public void connect() {
        if (isConnecting) {
            transferHandler.removeCallbacks(connectRunnable);
        }
        transferHandler.post(connectRunnable);
    }

    public void connectInternal() {
        if (isConnected) {
            return;
        }
        isConnecting = true;
        try {
            LogUtils.i("connect to " + mServerIp + ":" + mPort);
            socket = new Socket(mServerIp, mPort);
            outputStream = socket.getOutputStream();
            inputStream = socket.getInputStream();
            isConnected = true;
            runOnMainThread(() -> {
                if (connectListener != null) {
                    connectListener.onConnect(true);
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
            isConnected = false;
            runOnMainThread(() -> {
                if (connectListener != null) {
                    connectListener.onConnect(false);
                }
            });
        }
        isConnecting = false;
    }

    public void post(Image image) {
        ImageFrame imageFrame = new ImageFrame(image);
        ImageFrameWriter writer = new ImageFrameWriterImpl(imageFrame, outputStream);
        postTask(new ImageFrameTaskImpl(writer));
    }

    private void postTask(ImageFrameTask task) {
        if (!isConnected) {
            LogUtils.w("Socket未连接");
            return;
        }
        //如果排队任务超过3个则放弃本次任务，可以写成策略模式
        if (taskSize.get() > 3) {
            return;
        }
        taskSize.getAndIncrement();
        postTaskInternal(task);
    }

    private void postTaskInternal(ImageFrameTask task) {
        transferHandler.post(() -> {
            try {
                LogUtils.i("正在上传...");

                runOnMainThread(() -> {
                    if (transferListener != null) {
                        transferListener.onStartTransfer(task.getImageFrame());
                    }
                });
                task.run();
                LogUtils.i("上传成功...");

                runOnMainThread(() -> {
                    if (transferListener != null) {
                        transferListener.onTransferComplete(task.getImageFrame());
                    }
                });
                taskSize.getAndDecrement();
            } catch (IOException e) {
                e.printStackTrace();
                disconnect();
            }
        });
    }

    private void initTransferThread() {
        mainThreadHandler = new Handler(Looper.getMainLooper());
        transferThread = new HandlerThread("transfer_image_frame");
        transferThread.start();
        transferHandler = new Handler(transferThread.getLooper());
        taskSize.set(0);
    }

    public void disconnect() {
        if (socket != null && !socket.isClosed()) {
            Utils.close(outputStream);
            Utils.close(inputStream);
            Utils.close(socket);
            socket = null;
            outputStream = null;
            inputStream = null;
        }
        isConnected = false;
        runOnMainThread(() -> {
            if (connectListener != null) {
                connectListener.onDisconnect();
            }
        });
    }

    public void destroy() {
        disconnect();
        if (transferThread != null) {
            transferThread.quitSafely();
        }
    }

    public void setServerIp(String ip, int port) {
        this.mServerIp = ip;
        this.mPort = port;
    }

    public boolean isConnected() {
        return isConnected;
    }

    public void setTransferListener(OnTransferListener transferListener) {
        this.transferListener = transferListener;
    }

    public void setConnectListener(OnConnectListener connectListener) {
        this.connectListener = connectListener;
    }

    private void runOnMainThread(Runnable runnable) {
        mainThreadHandler.post(runnable);
    }

    public interface OnTransferListener {

        void onStartTransfer(ImageFrame frame);

        void onTransferComplete(ImageFrame frame);

    }

    public interface OnConnectListener {

        void onConnect(boolean isConnected);

        void onDisconnect();

    }

}
