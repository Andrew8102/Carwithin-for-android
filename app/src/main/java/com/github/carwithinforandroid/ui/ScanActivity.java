package com.github.carwithinforandroid.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 扫仪表投屏页的二维码，把里面的热点凭据返回给调用方。
 *
 * <p>用 Camera1 + TextureView + ZXing core，纯本地解码，不依赖 Google Play 服务 —— 现场
 * 车机旁可能没有可用网络，GMS 的条码 API 要联网下载模型，靠不住。
 *
 * <p>解码在后台线程做，主线程的相机回调只把 NV21 缓冲交出去；识别到任意二维码即返回，
 * 由 {@link com.github.carwithinforandroid.outputs.zeeho.ZeehoQr} 判断是不是我们要的格式。
 */
public final class ScanActivity extends Activity implements TextureView.SurfaceTextureListener {

    /** 扫码结果的 intent extra：二维码原始文本。 */
    public static final String EXTRA_QR = "qr_text";

    private static final int REQ_CAMERA = 2001;

    private TextureView preview;
    private TextView hint;
    private Camera camera;
    private int cameraId = -1;
    private Camera.Size previewSize;
    private HandlerThread decodeThread;
    private Handler decodeHandler;
    private final MultiFormatReader reader = new MultiFormatReader();
    private volatile boolean done = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#020617"));

        preview = new TextureView(this);
        preview.setSurfaceTextureListener(this);
        root.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 取景框提示（画在预览上方，纯装饰，不拦触摸）
        hint = new TextView(this);
        hint.setText("把仪表上的二维码放进框内\n识别后自动返回");
        hint.setTextColor(Color.parseColor("#E2E8F0"));
        hint.setTextSize(13f);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable tipBg = new GradientDrawable();
        tipBg.setColor(Color.parseColor("#CC0F172A"));
        tipBg.setCornerRadius(dp(10));
        hint.setBackground(tipBg);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        hp.topMargin = dp(48);
        root.addView(hint, hp);

        TextView quit = new TextView(this);
        quit.setText("点此取消");
        quit.setTextColor(Color.parseColor("#94A3B8"));
        quit.setTextSize(12f);
        quit.setPadding(dp(16), dp(12), dp(16), dp(28));
        quit.setClickable(true);
        quit.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams qp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        root.addView(quit, qp);

        setContentView(root);

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        List<BarcodeFormat> formats = new ArrayList<>();
        formats.add(BarcodeFormat.QR_CODE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, formats);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(hints);

        decodeThread = new HandlerThread("qr-decode");
        decodeThread.start();
        decodeHandler = new Handler(decodeThread.getLooper());

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CAMERA) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            hint.setText("没给相机权限，无法扫码。\n回主界面用 adb hotspot 命令写凭据");
            hint.postDelayed(this::finish, 2500);
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (camera != null) startPreview(surface);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        // 竖屏固定，尺寸变化可忽略
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // 用 setPreviewCallbackWithBuffer 取帧，不走这里
    }

    private void openCamera() {
        try {
            int count = Camera.getNumberOfCameras();
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < count; i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    cameraId = i;
                    break;
                }
            }
            if (cameraId < 0 && count > 0) cameraId = 0; // 只有前置也凑合
            if (cameraId < 0) {
                hint.setText("这台设备没有可用摄像头");
                return;
            }
            camera = Camera.open(cameraId);
            Camera.Parameters params = camera.getParameters();
            previewSize = pickPreviewSize(params.getSupportedPreviewSizes());
            params.setPreviewSize(previewSize.width, previewSize.height);
            params.setPreviewFormat(android.graphics.ImageFormat.NV21);
            List<String> modes = params.getSupportedFocusModes();
            if (modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
            camera.setParameters(params);
            // 相机方向跟屏幕一致，取景不歪
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            int degrees;
            switch (rotation) {
                case Surface.ROTATION_90: degrees = 90; break;
                case Surface.ROTATION_180: degrees = 180; break;
                case Surface.ROTATION_270: degrees = 270; break;
                default: degrees = 0;
            }
            Camera.CameraInfo ci = new Camera.CameraInfo();
            Camera.getCameraInfo(cameraId, ci);
            int orientation = (ci.orientation - degrees + 360) % 360;
            if (ci.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                orientation = (360 - orientation) % 360;
            }
            camera.setDisplayOrientation(orientation);

            if (preview.isAvailable()) startPreview(preview.getSurfaceTexture());
        } catch (Throwable e) {
            hint.setText("相机打开失败：" + e.getMessage());
        }
    }

    /** 挑一个接近 1280×720 的预览尺寸：够解码，又不至于太大拖慢。 */
    private Camera.Size pickPreviewSize(List<Camera.Size> sizes) {
        Camera.Size best = null;
        int target = 1280 * 720;
        int bestDiff = Integer.MAX_VALUE;
        for (Camera.Size s : sizes) {
            if (s.width < 640 || s.height < 360) continue;
            int diff = Math.abs(s.width * s.height - target);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = s;
            }
        }
        return best != null ? best : sizes.get(0);
    }

    private void startPreview(SurfaceTexture surface) {
        if (camera == null || previewSize == null) return;
        try {
            camera.setPreviewTexture(surface);
            int bufSize = previewSize.width * previewSize.height
                    * android.graphics.ImageFormat.getBitsPerPixel(android.graphics.ImageFormat.NV21) / 8;
            camera.setPreviewCallbackWithBuffer((data, cam) -> {
                if (done) return;
                cam.addCallbackBuffer(data); // 先还回去，避免丢帧卡住回调
                decode(data, previewSize.width, previewSize.height);
            });
            camera.addCallbackBuffer(new byte[bufSize]);
            camera.startPreview();
        } catch (Throwable e) {
            hint.setText("预览启动失败：" + e.getMessage());
        }
    }

    /** 后台线程解码，不阻塞相机回调。 */
    private void decode(byte[] data, int width, int height) {
        decodeHandler.post(() -> {
            if (done) return;
            try {
                PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                        data, width, height, 0, 0, width, height, false);
                Result result = reader.decode(new BinaryBitmap(new HybridBinarizer(source)));
                if (result != null && result.getText() != null) onScanned(result.getText());
            } catch (Throwable ignored) {
                // 每帧都可能解不出（没对准/模糊），正常，等下一帧
            }
        });
    }

    private void onScanned(String text) {
        if (done) return;
        done = true;
        runOnUiThread(() -> {
            hint.setText("已识别");
            Toast.makeText(this, "已识别二维码", Toast.LENGTH_SHORT).show();
        });
        Intent out = new Intent();
        out.putExtra(EXTRA_QR, text);
        setResult(RESULT_OK, out);
        finish();
    }

    @Override
    protected void onDestroy() {
        done = true;
        if (camera != null) {
            try {
                camera.setPreviewCallbackWithBuffer(null);
                camera.stopPreview();
                camera.release();
            } catch (Throwable ignored) {
            }
            camera = null;
        }
        if (decodeThread != null) decodeThread.quitSafely();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
