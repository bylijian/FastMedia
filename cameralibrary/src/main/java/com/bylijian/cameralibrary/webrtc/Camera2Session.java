/*
 *  Copyright 2016 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package com.bylijian.cameralibrary.webrtc;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.Range;
import android.view.Surface;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.bylijian.cameralibrary.webrtc.CameraEnumerationAndroid.CaptureFormat;

@TargetApi(21)
class Camera2Session implements CameraSession {
    private static final String TAG = "Camera2Session";
    private CaptureRequest.Builder captureRequestBuilder;
    private Size previewSize;

//  private static final Histogram camera2StartTimeMsHistogram =
//      Histogram.createCounts("WebRTC.Android.Camera2.StartTimeMs", 1, 10000, 50);
//  private static final Histogram camera2StopTimeMsHistogram =
//      Histogram.createCounts("WebRTC.Android.Camera2.StopTimeMs", 1, 10000, 50);
//  private static final Histogram camera2ResolutionHistogram = Histogram.createEnumeration(
//      "WebRTC.Android.Camera2.Resolution", CameraEnumerationAndroid.COMMON_RESOLUTIONS.size());

    private static enum SessionState {RUNNING, STOPPED}

    private final Handler cameraThreadHandler;
    private final CreateSessionCallback callback;
    private final Events events;
    private final Context applicationContext;
    private final CameraManager cameraManager;
    private final SurfaceTextureHelper surfaceTextureHelper;
    private final String cameraId;
    private final int width;
    private final int height;
    private final int framerate;

    // Initialized at start
    private CameraCharacteristics cameraCharacteristics;
    private int cameraOrientation;
    private boolean isCameraFrontFacing;
    private int fpsUnitFactor;
    private CaptureFormat captureFormat;

    // Initialized when camera opens
    @Nullable
    private CameraDevice cameraDevice;
    @Nullable
    private Surface surface;

    // Initialized when capture session is created
    @Nullable
    private CameraCaptureSession captureSession;

    // State
    private SessionState state = SessionState.RUNNING;
    private boolean firstFrameReported;

    // Used only for stats. Only used on the camera thread.
    private final long constructionTimeNs; // Construction time of this class.

    private MeteringRectangle[] mFocusArea;
    private MeteringRectangle[] mMeteringArea;

    private class CameraStateCallback extends CameraDevice.StateCallback {
        private String getErrorDescription(int errorCode) {
            switch (errorCode) {
                case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                    return "Camera device has encountered a fatal error.";
                case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                    return "Camera device could not be opened due to a device policy.";
                case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
                    return "Camera device is in use already.";
                case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
                    return "Camera service has encountered a fatal error.";
                case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                    return "Camera device could not be opened because"
                            + " there are too many other open camera devices.";
                default:
                    return "Unknown camera error: " + errorCode;
            }
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            checkIsOnCameraThread();
            final boolean startFailure = (captureSession == null) && (state != SessionState.STOPPED);
            state = SessionState.STOPPED;
            stopInternal();
            if (startFailure) {
                callback.onFailure(FailureType.DISCONNECTED, "Camera disconnected / evicted.");
            } else {
                events.onCameraDisconnected(Camera2Session.this);
            }
        }

        @Override
        public void onError(CameraDevice camera, int errorCode) {
            checkIsOnCameraThread();
            reportError(getErrorDescription(errorCode));
        }

        @Override
        public void onOpened(CameraDevice camera) {
            checkIsOnCameraThread();

            Log.d(TAG, "Camera opened.");
            cameraDevice = camera;

            surfaceTextureHelper.setTextureSize(captureFormat.width, captureFormat.height);
            surface = new Surface(surfaceTextureHelper.getSurfaceTexture());
            try {
                camera.createCaptureSession(
                        Arrays.asList(surface), new CaptureSessionCallback(), cameraThreadHandler);
            } catch (CameraAccessException e) {
                reportError("Failed to create capture session. " + e);
                return;
            }
        }

        @Override
        public void onClosed(CameraDevice camera) {
            checkIsOnCameraThread();

            Log.d(TAG, "Camera device closed.");
            events.onCameraClosed(Camera2Session.this);
        }
    }

    private class CaptureSessionCallback extends CameraCaptureSession.StateCallback {
        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            checkIsOnCameraThread();
            session.close();
            reportError("Failed to configure capture session.");
        }

        @Override
        public void onConfigured(CameraCaptureSession session) {
            checkIsOnCameraThread();
            Log.d(TAG, "Camera capture session configured.");
            captureSession = session;
            try {
                /*
                 * The viable options for video capture requests are:
                 * TEMPLATE_PREVIEW: High frame rate is given priority over the highest-quality
                 *   post-processing.
                 * TEMPLATE_RECORD: Stable frame rate is used, and post-processing is set for recording
                 *   quality.
                 */
                captureRequestBuilder =
                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                // Set auto exposure fps range.
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        new Range<Integer>(captureFormat.framerate.min / fpsUnitFactor,
                                captureFormat.framerate.max / fpsUnitFactor));
                captureRequestBuilder.set(
                        CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
                chooseStabilizationMode(captureRequestBuilder);
                chooseFocusMode(captureRequestBuilder);

                captureRequestBuilder.addTarget(surface);
                session.setRepeatingRequest(
                        captureRequestBuilder.build(), new CameraCaptureCallback(), cameraThreadHandler);
            } catch (CameraAccessException e) {
                reportError("Failed to start capture request. " + e);
                return;
            }

            surfaceTextureHelper.startListening((VideoFrame frame) -> {
                checkIsOnCameraThread();

                if (state != SessionState.RUNNING) {
                    Log.d(TAG, "Texture frame captured but camera is no longer running.");
                    return;
                }

                if (!firstFrameReported) {
                    firstFrameReported = true;
                    final int startTimeMs =
                            (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - constructionTimeNs);
//          camera2StartTimeMsHistogram.addSample(startTimeMs);
                }

                // Undo the mirror that the OS "helps" us with.
                // http://developer.android.com/reference/android/hardware/Camera.html#setDisplayOrientation(int)
                // Also, undo camera orientation, we report it as rotation instead.
                final VideoFrame modifiedFrame =
                        new VideoFrame(CameraSession.createTextureBufferWithModifiedTransformMatrix(
                                (TextureBufferImpl) frame.getBuffer(),
                                /* mirror= */ isCameraFrontFacing,
                                /* rotation= */ -cameraOrientation),
                                /* rotation= */ getFrameOrientation(), frame.getTimestampNs());
                events.onFrameCaptured(Camera2Session.this, modifiedFrame);
                modifiedFrame.release();
            });
            Log.d(TAG, "Camera device successfully started.");
            callback.onDone(Camera2Session.this);
        }

        // Prefers optical stabilization over software stabilization if available. Only enables one of
        // the stabilization modes at a time because having both enabled can cause strange results.
        private void chooseStabilizationMode(CaptureRequest.Builder captureRequestBuilder) {
            final int[] availableOpticalStabilization = cameraCharacteristics.get(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
            if (availableOpticalStabilization != null) {
                for (int mode : availableOpticalStabilization) {
                    if (mode == CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) {
                        captureRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
                        captureRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
                        Log.d(TAG, "Using optical stabilization.");
                        return;
                    }
                }
            }
            // If no optical mode is available, try software.
            final int[] availableVideoStabilization = cameraCharacteristics.get(
                    CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
            for (int mode : availableVideoStabilization) {
                if (mode == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
                    captureRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
                    Log.d(TAG, "Using video stabilization.");
                    return;
                }
            }
            Log.d(TAG, "Stabilization not available.");
        }

        private void chooseFocusMode(CaptureRequest.Builder captureRequestBuilder) {
            final int[] availableFocusModes =
                    cameraCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            for (int mode : availableFocusModes) {
                if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) {
                    captureRequestBuilder.set(
                            CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                    Log.d(TAG, "Using continuous video auto-focus.");
                    return;
                }
            }
            Log.d(TAG, "Auto-focus is not available.");
        }
    }

    private static class CameraCaptureCallback extends CameraCaptureSession.CaptureCallback {
        @Override
        public void onCaptureFailed(
                CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
            Log.d(TAG, "Capture failed: " + failure);
        }
    }

    public static void create(CreateSessionCallback callback, Events events,
                              Context applicationContext, CameraManager cameraManager,
                              SurfaceTextureHelper surfaceTextureHelper, String cameraId, int width, int height,
                              int framerate) {
        new Camera2Session(callback, events, applicationContext, cameraManager, surfaceTextureHelper,
                cameraId, width, height, framerate);
    }

    private Camera2Session(CreateSessionCallback callback, Events events, Context applicationContext,
                           CameraManager cameraManager, SurfaceTextureHelper surfaceTextureHelper, String cameraId,
                           int width, int height, int framerate) {
        Log.d(TAG, "Create new camera2 session on camera " + cameraId);

        constructionTimeNs = System.nanoTime();

        this.cameraThreadHandler = new Handler();
        this.callback = callback;
        this.events = events;
        this.applicationContext = applicationContext;
        this.cameraManager = cameraManager;
        this.surfaceTextureHelper = surfaceTextureHelper;
        this.cameraId = cameraId;
        this.width = width;
        this.height = height;
        this.framerate = framerate;

        start();
    }

    private void start() {
        checkIsOnCameraThread();
        Log.d(TAG, "start");

        try {
            cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
        } catch (final CameraAccessException e) {
            reportError("getCameraCharacteristics(): " + e.getMessage());
            return;
        }
        cameraOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        isCameraFrontFacing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING)
                == CameraMetadata.LENS_FACING_FRONT;

        findCaptureFormat();
        openCamera();
    }

    private void findCaptureFormat() {
        checkIsOnCameraThread();

        Range<Integer>[] fpsRanges =
                cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        fpsUnitFactor = Camera2Enumerator.getFpsUnitFactor(fpsRanges);
        List<CaptureFormat.FramerateRange> framerateRanges =
                Camera2Enumerator.convertFramerates(fpsRanges, fpsUnitFactor);
        List<Size> sizes = Camera2Enumerator.getSupportedSizes(cameraCharacteristics);
        Log.d(TAG, "Available preview sizes: " + sizes);
        Log.d(TAG, "Available fps ranges: " + framerateRanges);

        if (framerateRanges.isEmpty() || sizes.isEmpty()) {
            reportError("No supported capture formats.");
            return;
        }

        final CaptureFormat.FramerateRange bestFpsRange =
                CameraEnumerationAndroid.getClosestSupportedFramerateRange(framerateRanges, framerate);

        previewSize = CameraEnumerationAndroid.getClosestSupportedSize(sizes, width, height);
//    CameraEnumerationAndroid.reportCameraResolution(camera2ResolutionHistogram, bestSize);

        captureFormat = new CaptureFormat(previewSize.width, previewSize.height, bestFpsRange);
        Log.d(TAG, "Using capture format: " + captureFormat);
    }

    private void openCamera() {
        checkIsOnCameraThread();

        Log.d(TAG, "Opening camera " + cameraId);
        events.onCameraOpening();

        try {
            cameraManager.openCamera(cameraId, new CameraStateCallback(), cameraThreadHandler);
        } catch (CameraAccessException e) {
            reportError("Failed to open camera: " + e);
            return;
        }
    }

    @Override
    public void focus(Rect focus, Rect metering) {
        if (captureRequestBuilder == null || captureSession == null) {
            Log.e(TAG, "focus but captureSession null or captureRequestBuilder null");
            return;
        }
        checkIsOnCameraThread();
        CaptureRequest.Builder builder = captureRequestBuilder;
        int afMode = getValidAFMode(CaptureRequest.CONTROL_AF_MODE_AUTO);
        builder.set(CaptureRequest.CONTROL_AF_MODE, afMode);
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        if (mFocusArea == null) {
            mFocusArea = new MeteringRectangle[]{new MeteringRectangle(focus, 1000)};
        } else {
            mFocusArea[0] = new MeteringRectangle(focus, 1000);
        }
        if (mMeteringArea == null) {
            mMeteringArea = new MeteringRectangle[]{new MeteringRectangle(focus, 1000)};
        } else {
            mMeteringArea[0] = new MeteringRectangle(focus, 1000);
        }
        if (isMeteringSupport(true)) {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, mFocusArea);
        }
        if (isMeteringSupport(false)) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, mMeteringArea);
        }
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);

        try {
            captureSession.setRepeatingRequest(builder.build(), null, cameraThreadHandler);
            // trigger af
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            captureSession.capture(builder.build(), null, cameraThreadHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * fixme
     * 这个连续调用设置变焦的方法会导致华为手机的卡死
     * 变焦后对焦区域的计算不对，导致手动对焦模糊
     *
     * @param zoom
     */
    @Override
    public void setZoom(int zoom) {
        if (cameraDevice != null && cameraCharacteristics != null && captureRequestBuilder != null && captureSession != null) {
            int maxZoom = cameraCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM).intValue() * 10;
            Rect rect = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            int minW = rect.width() / maxZoom;
            int minH = rect.height() / maxZoom;
            int difW = rect.width() - minW;
            int difH = rect.height() - minH;
            int cropW = difW * zoom / 150;
            int cropH = difH * zoom / 150;
            cropW -= cropW & 3;
            cropH -= cropH & 3;
            Rect zoomRect = new Rect(cropW, cropH, rect.width() - cropW, rect.height() - cropH);
            Log.d(TAG, "handleZoom() zoom=" + zoom + "maxZoom=" + maxZoom + "zoomRect=" + zoomRect);
            captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoomRect);
            try {
                captureSession.setRepeatingRequest(
                        captureRequestBuilder.build(), new CameraCaptureCallback(), cameraThreadHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public Size getPreviewSize() {
        return previewSize == null ? new Size(0, 0) : previewSize;
    }

    @Override
    public CameraCharacteristics getCameraCharacteristics() {
        return cameraCharacteristics;
    }


    /* ------------------------- private function------------------------- */
    private int getValidAFMode(int targetMode) {
        int[] allAFMode = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        for (int mode : allAFMode) {
            if (mode == targetMode) {
                return targetMode;
            }
        }
        Log.i(TAG, "not support af mode:" + targetMode + " use mode:" + allAFMode[0]);
        return allAFMode[0];
    }

    private boolean isMeteringSupport(boolean focusArea) {
        int regionNum;
        if (focusArea) {
            regionNum = cameraCharacteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
        } else {
            regionNum = cameraCharacteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        }
        return regionNum > 0;
    }


    public CaptureRequest getTouch2FocusRequest(CaptureRequest.Builder builder,
                                                MeteringRectangle focus, MeteringRectangle metering) {
        int afMode = getValidAFMode(CaptureRequest.CONTROL_AF_MODE_AUTO);
        builder.set(CaptureRequest.CONTROL_AF_MODE, afMode);
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        if (mFocusArea == null) {
            mFocusArea = new MeteringRectangle[]{focus};
        } else {
            mFocusArea[0] = focus;
        }
        if (mMeteringArea == null) {
            mMeteringArea = new MeteringRectangle[]{metering};
        } else {
            mMeteringArea[0] = metering;
        }
        if (isMeteringSupport(true)) {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, mFocusArea);
        }
        if (isMeteringSupport(false)) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, mMeteringArea);
        }
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        return builder.build();
    }


    @Override
    public void stop() {
        Log.d(TAG, "Stop camera2 session on camera " + cameraId);
        checkIsOnCameraThread();
        if (state != SessionState.STOPPED) {
            final long stopStartTime = System.nanoTime();
            state = SessionState.STOPPED;
            stopInternal();
            final int stopTimeMs = (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStartTime);
//      camera2StopTimeMsHistogram.addSample(stopTimeMs);
        }
    }

    private void stopInternal() {
        Log.d(TAG, "Stop internal");
        checkIsOnCameraThread();

        surfaceTextureHelper.stopListening();

        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }

        Log.d(TAG, "Stop done");
    }

    private void reportError(String error) {
        checkIsOnCameraThread();
        Log.e(TAG, "Error: " + error);

        final boolean startFailure = (captureSession == null) && (state != SessionState.STOPPED);
        state = SessionState.STOPPED;
        stopInternal();
        if (startFailure) {
            callback.onFailure(FailureType.ERROR, error);
        } else {
            events.onCameraError(this, error);
        }
    }

    private int getFrameOrientation() {
        int rotation = CameraSession.getDeviceOrientation(applicationContext);
        if (!isCameraFrontFacing) {
            rotation = 360 - rotation;
        }
        return (cameraOrientation + rotation) % 360;
    }

    private void checkIsOnCameraThread() {
        if (Thread.currentThread() != cameraThreadHandler.getLooper().getThread()) {
            throw new IllegalStateException("Wrong thread");
        }
    }

}
