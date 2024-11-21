package com.mobileapp.iot_sign_language_recognition;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import android.Manifest;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.mobileapp.iot_sign_language_recognition.databinding.ActivityMainBinding;

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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding viewBinding;
    private ExecutorService camExecutor;
    private boolean isCapturing = false;
    private long lastCaptureTime = 0;
    public PreviewView previewView;
    public Button capture;
    public Button finish;
    int cameraFacing = CameraSelector.LENS_FACING_BACK;

    private final ActivityResultLauncher<String> activityResultLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), new ActivityResultCallback<Boolean>() {
        @Override
        public void onActivityResult(Boolean result) {
            if (result){
                startCamera(cameraFacing);
            }
        }
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        viewBinding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(viewBinding.getRoot());

        previewView = findViewById(R.id.previewView);
        capture = findViewById(R.id.capture);
        finish = findViewById(R.id.finish);

        // init OpenCV
        if (!OpenCVLoader.initDebug()) {
            Log.e("ASL", "OpenCV initialization failed");
            Toast.makeText(this, "OpenCV initialization failed", Toast.LENGTH_LONG).show();
            return;
        }

        // setup executor
        camExecutor = Executors.newSingleThreadExecutor();

        // start camera if permissions allow
        if(ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            activityResultLauncher.launch(Manifest.permission.CAMERA);
        }

        capture.setOnClickListener(v -> {
            isCapturing = true;
            Toast.makeText(this, "Image capture in progress", Toast.LENGTH_LONG).show();
            startCamera(cameraFacing);
        });

        finish.setOnClickListener(v -> {
            isCapturing = false;
            Toast.makeText(this, "Image capture complete", Toast.LENGTH_LONG).show();
        });

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (camExecutor != null) {
            camExecutor.shutdown();
        }
    }

    /**
     * begin using the camera, and set up preview for the user
     *
     * @param cameraFacing dictates the direction of the camera orientation
     */
    public void startCamera(int cameraFacing){
        int aspectRatio = aspectRatio(previewView.getWidth(), previewView.getHeight());
        ListenableFuture<ProcessCameraProvider> listenableFuture = ProcessCameraProvider.getInstance(this);
        listenableFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = (ProcessCameraProvider) listenableFuture.get();

                // Camera components
                Preview preview = new Preview.Builder().setTargetAspectRatio(aspectRatio).build();

                ImageCapture imageCapture = new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(getWindowManager().getDefaultDisplay().getRotation()).build();

                CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(cameraFacing).build();

                cameraProvider.unbindAll();

                Camera camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

                // for photo capture using cameraX
//                takePhoto.setOnClickListener(new View.OnClickListener() {
//                    @Override
//                    public void onClick(View v) {
//                        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED){
//                            activityResultLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
//                        } else {
//                            takePicture(imageCapture);
//                        }
//                    }
//                });

                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                imageAnalysis.setAnalyzer(camExecutor, imageProxy -> {
                    long currentTime = System.currentTimeMillis();
                    if(isCapturing && currentTime - lastCaptureTime >= 5000) {
                        lastCaptureTime = currentTime;
                        Mat mat = imageProxyToMat(imageProxy);
                        if(mat != null) {
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
            } catch(ExecutionException | InterruptedException e){
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // For capturing the image
//    public void takePicture(ImageCapture imageCapture) {
//        final File file = new File(getExternalFilesDir(null), System.currentTimeMillis() + ".jpg");
//        ImageCapture.OutputFileOptions outputFileOptions = new ImageCapture.OutputFileOptions.Builder(file).build();
//        imageCapture.takePicture(outputFileOptions, Executors.newCachedThreadPool(), new ImageCapture.OnImageSavedCallback() {
//            @Override
//            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
//                runOnUiThread(new Runnable() {
//                    @Override
//                    public void run() {
//                        Toast.makeText(MainActivity.this, "Image saved at: " + file.getPath(), Toast.LENGTH_LONG).show();
//                    }
//                });
//                startCamera(cameraFacing);
//            }
//
//            @Override
//            public void onError(@NonNull ImageCaptureException exception) {
//                runOnUiThread(new Runnable() {
//                    @Override
//                    public void run() {
//                        Toast.makeText(MainActivity.this, "Image saved at: " + file.getPath(), Toast.LENGTH_LONG).show();
//                    }
//                });
//                startCamera(cameraFacing);
//            }
//        });
//    }

    /**
     * convert an androidX image to an OpenCV matrix object
     *
     * @param imageProxy wrapper for an image captured by androidX to make image data easier to access
     * @return openCV matrix object
     */
    private Mat imageProxyToMat(ImageProxy imageProxy){
        try {
            ByteBuffer buffer = imageProxy.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            Mat mat = new Mat(imageProxy.getHeight(), imageProxy.getWidth(), CvType.CV_8UC1);
            mat.put(0,0, bytes);
            return mat;
        } catch (Exception e ) {
            Log.e("ASL", "Error converting image proxy");
            return null;
        }
    }

    /**
     * draws a green rectangle around the target
     *
     * @param mat an openCV matrix object
     */
    private void drawGreenRectangle(Mat mat) {
        Imgproc.rectangle(mat,
                new Point(mat.cols() / 4.0, mat.rows() / 4.0),
                new Point(mat.cols() * 3.0 / 4.0, mat.rows() * 3.0 / 4.0),
                new Scalar(0, 255, 0), 5);
    }

    /**
     * saves the given frame into external stoarge
     * @param mat openCV matrix object
     */
    private void saveFrame(Mat mat) {
        Bitmap bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(mat, bitmap);

        File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "CameraXOpenCV");
        if (!directory.exists() && !directory.mkdirs()) {
            Log.e("ASL", "Failed to create directory");
            return;
        }

        String fileName = "Capture_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + ".jpg";
        File file = new File(directory, fileName);

        try (FileOutputStream out = new FileOutputStream(file)) {
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)) {
                Log.d("ASL", "Saved: " + file.getAbsolutePath());
                runOnUiThread(() -> Toast.makeText(this, "Saved: " + file.getAbsolutePath(), Toast.LENGTH_SHORT).show());
            } else {
                Log.e("ASL", "Failed to compress and save bitmap");
            }
        } catch (Exception e) {
            Log.e("ASL", "Error saving bitmap", e);
        }
    }

    private int aspectRatio(int width, int height) {
        double previewRatio = (double) Math.max(width,height) / Math.min(width, height);
        if(Math.abs(previewRatio-4.0/3.0) <= Math.abs(previewRatio - 16.0/9.0)){
            return AspectRatio.RATIO_4_3;
        }
        return AspectRatio.RATIO_16_9;
    }
}