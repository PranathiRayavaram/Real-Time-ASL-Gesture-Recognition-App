package com.example.asl;


import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;


import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;


import com.example.asl.ml.AslModel;
import com.google.common.util.concurrent.ListenableFuture;


import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class MainActivity extends AppCompatActivity {


    private static final String TAG = "MainActivity";
    private static final int CAMERA_PERMISSION_CODE = 1001;
    private boolean isCapturing = false;
    private ExecutorService cameraExecutor;
    private PreviewView previewView;
    private OverlayView overlayView;
    private ImageCapture imageCapture;


    private Handler handler;
    private Runnable captureRunnable;


    private TextView resultTextView; // TextView to display predictions


    private final List<String> classLabels = Arrays.asList(
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J",
            "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T",
            "U", "V", "W", "X", "Y", "Z", "del", "nothing", "space"
    );


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
        previewView = findViewById(R.id.previewView);
        resultTextView = findViewById(R.id.resultTextView); // Initialize resultTextView


        // Initialize the handler and captureRunnable
        handler = new Handler();


        captureRunnable = new Runnable() {
            @Override
            public void run() {
                if (isCapturing) {
                    capturePhoto();
                    handler.postDelayed(this, 3000); // Capture every 3 seconds
                }
            }
        };


        // Start the camera
        startCamera();


        startButton.setOnClickListener(v -> {
            if (!isCapturing) {
                isCapturing = true;
                Toast.makeText(this, "Image capturing started", Toast.LENGTH_LONG).show();
                handler.post(captureRunnable);
            }
        });


        stopButton.setOnClickListener(v -> {
            if (isCapturing) {
                isCapturing = false;
                handler.removeCallbacks(captureRunnable);
                Toast.makeText(this, "Image capturing stopped", Toast.LENGTH_LONG).show();
            }


            resultTextView.setVisibility(View.VISIBLE);
            resultTextView.setText("Result will appear here. Click on 'Start' button");
        });


        // Initialize and add the overlay view
        //overlayView = new OverlayView(this);
//        addContentView(overlayView, new PreviewView.LayoutParams(
//                PreviewView.LayoutParams.MATCH_PARENT,
//                PreviewView.LayoutParams.MATCH_PARENT));
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


                    androidx.camera.core.Preview preview = new androidx.camera.core.Preview.Builder().build();
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());


                    // Initialize ImageCapture for high-quality image capture
                    imageCapture = new ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .setTargetRotation(getWindowManager().getDefaultDisplay().getRotation())
                            .build();


                    // Bind to lifecycle
                    cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);


                } catch (Exception e) {
                    Log.e(TAG, "Use case binding failed", e);
                }
            }, ContextCompat.getMainExecutor(this));
        } else {
            Toast.makeText(this, "Camera permission is required to start camera", Toast.LENGTH_LONG).show();
        }
    }


    private void capturePhoto() {
        if (imageCapture == null) {
            return;
        }


        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                // Convert ImageProxy to Bitmap
                Bitmap bitmap = imageProxyToBitmap(image);


                // Rotate the bitmap if necessary
                bitmap = rotateBitmap(bitmap, image.getImageInfo().getRotationDegrees());


                // Crop the bitmap to the area of the overlay box
                //Bitmap croppedBitmap = cropBitmapToOverlay(bitmap);


                // Resize the cropped bitmap to 64x64 pixels using OpenCV
                Bitmap resizedBitmap = resizeBitmapWithOpenCV(bitmap, 64, 64);


                // Run prediction on the resized image
                runModel(resizedBitmap);


                // Save the resized bitmap to a file
                saveBitmap(resizedBitmap);
                saveBitmapToFile(resizedBitmap);


                // Close the image to release resources
                image.close();


                // Clean up the bitmaps
                if (!bitmap.isRecycled()) {
                    bitmap.recycle();
                }
//                if (!croppedBitmap.isRecycled()) {
//                    croppedBitmap.recycle();
//                }
                if (!resizedBitmap.isRecycled()) {
                    resizedBitmap.recycle();
                }
            }


            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e(TAG, "Photo capture failed: " + exception.getMessage(), exception);
            }
        });
    }


    private Bitmap imageProxyToBitmap(ImageProxy image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }


    private Bitmap rotateBitmap(Bitmap bitmap, int degrees) {
        if (degrees == 0) {
            return bitmap;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }


    private Bitmap cropBitmapToOverlay(Bitmap bitmap) {
        Rect overlayRect = overlayView.getOverlayRect();
        return Bitmap.createBitmap(bitmap, overlayRect.left, overlayRect.top, overlayRect.width(), overlayRect.height());
    }


    private Bitmap resizeBitmapWithOpenCV(Bitmap bitmap, int width, int height) {
        Mat mat = new Mat();
        Utils.bitmapToMat(bitmap, mat);
        Imgproc.resize(mat, mat, new Size(width, height));
        Bitmap resizedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(mat, resizedBitmap);
        return resizedBitmap;
    }


    private void saveBitmap(Bitmap bitmap) {
        // Placeholder function if additional bitmap saving is required
    }


    private void runModel(Bitmap bitmap) {
        try {
            AslModel model = AslModel.newInstance(this);


            // Prepare the input buffer
            TensorBuffer inputFeature0 = TensorBuffer.createFixedSize(new int[]{1, 64, 64, 3}, DataType.FLOAT32);
            ByteBuffer byteBuffer = preprocessImage(bitmap);
            inputFeature0.loadBuffer(byteBuffer);


            // Perform inference
            AslModel.Outputs outputs = model.process(inputFeature0);
            TensorBuffer outputFeature0 = outputs.getOutputFeature0AsTensorBuffer();


            // Get the prediction
            float[] outputArray = outputFeature0.getFloatArray();
            int maxIndex = getMaxIndex(outputArray);
            String prediction = classLabels.get(maxIndex);
            float confidence = outputArray[maxIndex];


            // Display prediction
            runOnUiThread(() -> resultTextView.setText("Prediction: " + prediction + " (" + String.format("%.2f", confidence * 100) + "%)"));


            model.close();
        } catch (Exception e) {
            Log.e(TAG, "Model inference failed", e);
        }
    }


    private ByteBuffer preprocessImage(Bitmap bitmap) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(64 * 64 * 3 * 4);
        buffer.order(ByteOrder.nativeOrder());


        int[] pixels = new int[64 * 64];
        bitmap.getPixels(pixels, 0, 64, 0, 0, 64, 64);


        for (int pixel : pixels) {
            buffer.putFloat(((pixel >> 16) & 0xFF) / 255.0f);
            buffer.putFloat(((pixel >> 8) & 0xFF) / 255.0f);
            buffer.putFloat((pixel & 0xFF) / 255.0f);
        }
        return buffer;
    }


    private int getMaxIndex(float[] probabilities) {
        int maxIndex = 0;
        float maxProbability = probabilities[0];
        for (int i = 1; i < probabilities.length; i++) {
            if (probabilities[i] > maxProbability) {
                maxProbability = probabilities[i];
                maxIndex = i;
            }
        }
        return maxIndex;
    }


    private void saveBitmapToFile(Bitmap bitmap) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "asl_image_" + System.currentTimeMillis() + ".png");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ASL_Images");


            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out != null) {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                        runOnUiThread(() -> Toast.makeText(this, "Image saved: " + uri.toString(), Toast.LENGTH_LONG).show());
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Failed to save image", e);
                }
            } else {
                Log.e(TAG, "Failed to create new MediaStore record.");
            }
        } else {
            File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ASL_Images");
            if (!directory.exists()) {
                directory.mkdirs();
            }


            String fileName = "asl_image_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".png";
            File file = new File(directory, fileName);


            runOnUiThread(() -> {
                try (FileOutputStream out = new FileOutputStream(file)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                    Toast.makeText(this, "Image saved: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to save image", e);
                }
            });
        }
    }


    // Custom Overlay View class
    private class OverlayView extends View {
        private Paint paint;
        private Rect overlayRect;


        public OverlayView(Context context) {
            super(context);
            init();
        }


        private void init() {
            paint = new Paint();
            paint.setColor(android.graphics.Color.GREEN);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(5f);
        }


        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);


            // Define the box size as a percentage of the minimum dimension (width or height)
            float boxSizePercentage = 0.9f; // Adjusted to 90% for a large square


            // Get the dimensions of the canvas
            float canvasWidth = canvas.getWidth();
            float canvasHeight = canvas.getHeight();


            // Calculate the minimum dimension to ensure the box is square
            float minDimension = Math.min(canvasWidth, canvasHeight);


            // Calculate the box size
            float boxSize = minDimension * boxSizePercentage;


            // Calculate the coordinates to center the box
            float centerX = canvasWidth / 2f;
            float centerY = canvasHeight / 2f;


            float left = centerX - boxSize / 2f;
            float top = centerY - boxSize / 2f;
            float right = centerX + boxSize / 2f;
            float bottom = centerY + boxSize / 2f;


            // Draw the overlay rectangle
            canvas.drawRect(left, top, right, bottom, paint);


            // Store the overlay rectangle for use in cropping
            overlayRect = new Rect((int) left, (int) top, (int) right, (int) bottom);
        }


        // Provide a method to get the overlay rectangle
        public Rect getOverlayRect() {
            return overlayRect;
        }
    }
}
