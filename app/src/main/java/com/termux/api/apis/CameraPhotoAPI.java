package com.termux.api.apis;

import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;

import androidx.annotation.NonNull;

import com.termux.api.TermuxApiReceiver;
import com.termux.shared.logger.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;

public class CameraPhotoAPI {

    private static final String LOG_TAG = "CameraPhotoAPI";

    public static void onReceive(TermuxApiReceiver apiReceiver, final Context context, final Intent intent) {
        final String filePath = intent.getStringExtra("file");
        final String cameraId = intent.getStringExtra("camera");
        final String customSize = intent.getStringExtra("size");

        if (filePath == null) {
            Logger.logError(LOG_TAG, "No output file specified.");
            return;
        }

        final File outputFile = new File(filePath);
        final File outputDir = outputFile.getParentFile();

        if (outputDir != null && !outputDir.exists() && !outputDir.mkdirs()) {
            Logger.logError(LOG_TAG, "Cannot create directory: " + outputDir.getAbsolutePath());
            return;
        }

        takePhoto(context, cameraId, outputFile, customSize);
    }

    private static void takePhoto(final Context context, final String requestedCameraId, final File outputFile, final String customSize) {
        final CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) return;

        final HandlerThread backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        final Handler backgroundHandler = new Handler(backgroundThread.getLooper());

        try {
            String targetCameraId = requestedCameraId != null ? requestedCameraId : "0";
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(targetCameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            Size selectedSize = null;
            if (map != null) {
                Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
                if (customSize != null && customSize.contains("x")) {
                    String[] parts = customSize.split("x");
                    int w = Integer.parseInt(parts[0].trim());
                    int h = Integer.parseInt(parts[1].trim());
                    for (Size s : sizes) {
                        if ((s.getWidth() == w && s.getHeight() == h) || (s.getWidth() == h && s.getHeight() == w)) {
                            selectedSize = s;
                            break;
                        }
                    }
                }
                if (selectedSize == null && sizes != null && sizes.length > 0) {
                    selectedSize = sizes[0];
                }
            }

            if (selectedSize == null) selectedSize = new Size(1280, 720);

            final ImageReader reader = ImageReader.newInstance(selectedSize.getWidth(), selectedSize.getHeight(), ImageFormat.JPEG, 1);

            manager.openCamera(targetCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull final CameraDevice camera) {
                    try {
                        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                            @Override
                            public void onImageAvailable(ImageReader reader) {
                                Image image = null;
                                try {
                                    image = reader.acquireLatestImage();
                                    if (image != null) {
                                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                                        byte[] bytes = new byte[buffer.remaining()];
                                        buffer.get(bytes);
                                        try (FileOutputStream output = new FileOutputStream(outputFile)) {
                                            output.write(bytes);
                                        }
                                    }
                                } catch (IOException e) {
                                    Logger.logError(LOG_TAG, "Failed writing image: " + e.getMessage());
                                } finally {
                                    if (image != null) image.close();
                                    reader.close();
                                    camera.close();
                                    backgroundThread.quitSafely();
                                }
                            }
                        }, backgroundHandler);

                        camera.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(@NonNull CameraCaptureSession session) {
                                try {
                                    CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                    builder.addTarget(reader.getSurface());
                                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                    session.capture(builder.build(), null, backgroundHandler);
                                } catch (CameraAccessException e) {
                                    camera.close();
                                }
                            }

                            @Override
                            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                camera.close();
                            }
                        }, backgroundHandler);

                    } catch (CameraAccessException e) {
                        camera.close();
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                }
            }, backgroundHandler);

        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Camera error: " + e.getMessage());
            backgroundThread.quitSafely();
        }
    }
                }
