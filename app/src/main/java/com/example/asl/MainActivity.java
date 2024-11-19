package com.example.asl;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int CAMERA_PERMISSION_CODE = 1001;
    private boolean isCapturing = false;
    private ExecutorService cameraExecutor;
    private long lastCaptureTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize OpenCV
        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "OpenCV initialization failed");
            Toast.makeText(this, "OpenCV initialization failed", Toast.LENGTH_LONG).show();
            return;
        }

        // Request necessary permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, CAMERA_PERMISSION_CODE);
        }

        // Set up executor
        cameraExecutor = Executors.newSingleThreadExecutor();

        // Setup Start and Stop buttons
        Button startButton = findViewById(R.id.startButton);
        Button stopButton = findViewById(R.id.stopButton);

        startButton.setOnClickListener(v -> {
            isCapturing = true;
            Toast.makeText(this, "Image capturing started", Toast.LENGTH_LONG).show();
            startCamera();
        });

        stopButton.setOnClickListener(v -> {
            isCapturing = false;
            Toast.makeText(this, "Image capturing stopped", Toast.LENGTH_LONG).show();
        });
    }

    private void startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
            cameraProviderFuture.addListener(() -> {
                try {
                    ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                    CameraSelector cameraSelector = new CameraSelector.Builder()
                            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                            .build();

                    androidx.camera.view.PreviewView previewView = findViewById(R.id.previewView);
                    androidx.camera.core.Preview preview = new androidx.camera.core.Preview.Builder().build();
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());


                    // ImageAnalysis for frame processing
                    ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build();

                    imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                        long currentTime = System.currentTimeMillis();
                        if (isCapturing && currentTime - lastCaptureTime >= 5000) { // Capture every 5 seconds
                            lastCaptureTime = currentTime;
                            Mat mat = imageProxyToMat(imageProxy);
                            if (mat != null) {

                                saveFrame(mat);
                                Bitmap processedBitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);
                                Utils.matToBitmap(mat, processedBitmap);
                                runOnUiThread(() -> {
                                    previewView.getOverlay().clear();
                                    previewView.getOverlay().add(new BitmapDrawable(getResources(), processedBitmap));
                                });
                            }
                        }
                        imageProxy.close();
                    });

                    // Bind to lifecycle
                    cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
                } catch (Exception e) {
                    Log.e(TAG, "Use case binding failed", e);
                }
            }, ContextCompat.getMainExecutor(this));
        } else {
            Toast.makeText(this, "Camera permission is required to start camera", Toast.LENGTH_LONG).show();
        }
    }

    private Mat imageProxyToMat(ImageProxy imageProxy) {
        try {
            ByteBuffer buffer = imageProxy.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            Mat mat = new Mat(imageProxy.getHeight(), imageProxy.getWidth(), CvType.CV_8UC1);
            mat.put(0, 0, bytes);
            return mat;
        } catch (Exception e) {
            Log.e(TAG, "Error converting ImageProxy to Mat", e);
            return null;
        }
    }

    private void drawGreenRectangle(Mat mat) {
        Imgproc.rectangle(mat,
                new Point(mat.cols() / 4.0, mat.rows() / 4.0),
                new Point(mat.cols() * 3.0 / 4.0, mat.rows() * 3.0 / 4.0),
                new Scalar(0, 255, 0), 5);
    }

    private void saveFrame(Mat mat) {
        Bitmap bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(mat, bitmap);

        File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "CameraXOpenCV");
        if (!directory.exists() && !directory.mkdirs()) {
            Log.e(TAG, "Failed to create directory");
            return;
        }

        String fileName = "Capture_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + ".jpg";
        File file = new File(directory, fileName);

        try (FileOutputStream out = new FileOutputStream(file)) {
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)) {
                Log.d(TAG, "Saved: " + file.getAbsolutePath());
                runOnUiThread(() -> Toast.makeText(this, "Saved: " + file.getAbsolutePath(), Toast.LENGTH_SHORT).show());
            } else {
                Log.e(TAG, "Failed to compress and save bitmap");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving bitmap", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_LONG).show();
            }
        }
    }
}
