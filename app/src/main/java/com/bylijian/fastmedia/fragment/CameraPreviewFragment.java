package com.bylijian.fastmedia.fragment;

import android.app.Activity;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;


import com.bylijian.fastmedia.R;
import com.bylijian.fastmedia.model.AspectRatio;
import com.bylijian.fastmedia.widget.AspectFrameLayout;
import com.bylijian.fastmedia.widget.FocusView;
import com.bylijian.fastmedia.widget.RatioImageView;
import com.bylijian.fastmedia.widget.ShutterButton;
import com.bylijian.cameralibrary.CameraRecorder;
import com.bylijian.cameralibrary.FocusManager;
import com.bylijian.cameralibrary.webrtc.Camera1Enumerator;
import com.bylijian.cameralibrary.webrtc.Camera2Enumerator;
import com.bylijian.cameralibrary.webrtc.CameraEnumerator;
import com.bylijian.cameralibrary.webrtc.CameraVideoCapturer;
import com.bylijian.cameralibrary.webrtc.CapturerObserver;
import com.bylijian.cameralibrary.webrtc.EglBase;
import com.bylijian.cameralibrary.webrtc.RendererCommon;
import com.bylijian.cameralibrary.webrtc.Size;
import com.bylijian.cameralibrary.webrtc.SurfaceTextureHelper;
import com.bylijian.cameralibrary.webrtc.SurfaceViewRenderer;
import com.bylijian.cameralibrary.webrtc.VideoFrame;


public class CameraPreviewFragment extends Fragment implements View.OnClickListener, RatioImageView.OnRatioChangedListener, ShutterButton.OnShutterListener {
    private static final String TAG = "CameraPreviewFragment";
    private SurfaceViewRenderer surfaceViewRenderer;
    private CameraVideoCapturer videoCapturer;
    private SurfaceTextureHelper surfaceTextureHelper;
    final EglBase eglBase = EglBase.create();

    private ImageView closeButton;
    private LinearLayout selectMusic;
    private ImageView switchCamera;
    private RatioImageView ratioImageView;
    private AspectFrameLayout aspectFrameLayout;
    private FocusView focusView;
    private ShutterButton shutterButton;

    private FocusManager focusManager;
    private boolean isShowingFilters;
    private int mFilterIndex = 0;

    private CameraRecorder cameraRecorder;

    private int cameraZoom = 1;

    private Size cameraPreviewSize = new Size(1920, 1080);

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate()");
        focusManager = new FocusManager();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Log.d(TAG, "onCreateView()");
        View view = inflater.inflate(R.layout.fragment_preview, container, false);
        initView(view);
        videoCapturer = createVideoCapturer();
        videoCapturer.initialize(surfaceTextureHelper, getContext(), new CapturerObserver() {
            @Override
            public void onCapturerStarted(boolean success, Size previewSize, CameraCharacteristics cameraCharacteristics) {
                Log.d(TAG, "onCapturerStarted() success=" + success);
                if (previewSize != null) {
                    focusManager.onPreviewChanged(previewSize.width, previewSize.height, cameraCharacteristics);
                }
                if (shutterButton != null && !shutterButton.isEnableOpened()) {
                    shutterButton.setEnableOpened(true);
                }
            }

            @Override
            public void onCapturerStopped() {
                Log.d(TAG, "onCapturerStopped()");
            }

            @Override
            public void onFrameCaptured(VideoFrame frame) {
                Log.d(TAG, "onFrameCaptured()");
                if (cameraRecorder != null) {
                    cameraRecorder.onVideoFrame(frame);
                }
                surfaceViewRenderer.onFrame(frame);
            }
        });
        return view;
    }

    private void initView(View view) {
        surfaceViewRenderer = view.findViewById(R.id.surfaceviewrender);
        aspectFrameLayout = view.findViewById(R.id.layout_aspect);
        aspectFrameLayout.setAspectRatio(9.0 / 16);
        closeButton = view.findViewById(R.id.btn_close);
        selectMusic = view.findViewById(R.id.btn_music);
        switchCamera = view.findViewById(R.id.btn_switch);
        ratioImageView = view.findViewById(R.id.iv_ratio);
        focusView = view.findViewById(R.id.focusview);
        shutterButton = view.findViewById(R.id.btn_shutter);
        shutterButton.setIsRecorder(true);
        shutterButton.setOnShutterListener(this);
        initSurfaceViewRenderer();
        closeButton.setOnClickListener(this);
        selectMusic.setOnClickListener(this);
        switchCamera.setOnClickListener(this);
        ratioImageView.addRatioChangedListener(this);
    }

    private void initSurfaceViewRenderer() {
        if (surfaceViewRenderer != null) {
            surfaceViewRenderer.init(eglBase.getEglBaseContext(), null);
            surfaceViewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
            surfaceViewRenderer.setEnableHardwareScaler(false);
            surfaceViewRenderer.setonGestureListener(new SurfaceViewRenderer.OnGestureListener() {
                @Override
                public void onSingleClick(float x, float y) {
                    if (videoCapturer != null && focusManager != null) {
                        videoCapturer.focus(focusManager.getFocusArea(x, y, true), focusManager.getFocusArea(x, y, false));
                    }
                    if (focusView != null) {
                        focusView.showFocusView(x, y);
                    }
                }

                @Override
                public void onDoubleClick() {
                    if (videoCapturer != null) {
                        cameraZoom += 20;
                        if (cameraZoom < 1) {
                            cameraZoom = 1;
                        } else if (cameraZoom > 100) {
                            cameraZoom = 100;
                        }
                        videoCapturer.setZoom(cameraZoom);
                    }
                }

                @Override
                public void onScaleDiff(float diff) {
                    Log.d(TAG, "onScaleDiff() diff=" + diff);
                    if (videoCapturer != null) {
                        cameraZoom = (int) (cameraZoom + diff * 100 / surfaceViewRenderer.getWidth());
                        if (cameraZoom < 1) {
                            cameraZoom = 1;
                        } else if (cameraZoom > 100) {
                            cameraZoom = 100;
                        }
                        videoCapturer.setZoom(cameraZoom);
                    }
                }
            });

        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (videoCapturer != null) {
            videoCapturer.startCapture(cameraPreviewSize.width, cameraPreviewSize.height, 25);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (videoCapturer != null) {
            try {
                videoCapturer.stopCapture();
            } catch (InterruptedException e) {
                e.printStackTrace();
                Log.e(TAG, "stopCapture exception", e);
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (videoCapturer != null) {
            videoCapturer.dispose();
        }
        if (surfaceViewRenderer != null) {
            surfaceViewRenderer.release();
        }
        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.dispose();
        }
    }

    @Nullable
    private CameraVideoCapturer createVideoCapturer() {
        CameraVideoCapturer videoCapturer;
        surfaceTextureHelper =
                SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
        if (useCamera2()) {
            Log.d(TAG, "Creating capturer using camera2 API.");
            videoCapturer = createCameraCapturer(new Camera2Enumerator(requireContext()));
        } else {
            Log.d(TAG, "Creating capturer using camera1 API.");
            videoCapturer = createCameraCapturer(new Camera1Enumerator(captureToTexture()));
        }
        if (videoCapturer == null) {
            Log.e(TAG, "Failed to open camera");
            return null;
        }
        return videoCapturer;
    }

    private boolean captureToTexture() {
        return true;
    }

    private boolean useCamera2() {
        return true;
    }

    private @Nullable
    CameraVideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
        final String[] deviceNames = enumerator.getDeviceNames();

        // Front back camera not found, try something else
        Log.d(TAG, "Looking for back cameras.");
        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                Log.d(TAG, "Creating other camera capturer.");
                CameraVideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        // then, try to find front facing camera
        Log.d(TAG, "Looking for front facing cameras.");
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                Log.d(TAG, "Creating front facing camera capturer.");
                CameraVideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }
        return null;
    }


    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_close:
                Activity activity = getActivity();
                if (activity != null) {
                    activity.finish();
                }
                break;
            case R.id.btn_switch:
                if (videoCapturer != null) {
                    videoCapturer.switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
                        @Override
                        public void onCameraSwitchDone(boolean isFrontCamera) {
                            if (surfaceViewRenderer != null) {
                                surfaceViewRenderer.setMirror(isFrontCamera);
                            }
                        }

                        @Override
                        public void onCameraSwitchError(String errorDescription) {

                        }
                    });
                }
                break;
        }
    }

    @Override
    public void onRatioChanged(AspectRatio type) {
        switch (type) {
            case RATIO_1_1:
                aspectFrameLayout.setAspectRatio(1);
                cameraPreviewSize.width = 720;
                cameraPreviewSize.height = 720;
                resizeVideoCapturer(cameraPreviewSize.width, cameraPreviewSize.height);
                break;
            case RATIO_4_3:
                aspectFrameLayout.setAspectRatio(3.0 / 4);
                cameraPreviewSize.width = 960;
                cameraPreviewSize.height = 720;
                resizeVideoCapturer(cameraPreviewSize.width, cameraPreviewSize.height);
                break;

            case Ratio_16_9:
                aspectFrameLayout.setAspectRatio(9.0 / 16);
                cameraPreviewSize.width = 1920;
                cameraPreviewSize.height = 1080;
                resizeVideoCapturer(cameraPreviewSize.width, cameraPreviewSize.height);
                break;
        }
    }

    private void resizeVideoCapturer(int width, int height) {
        try {
            videoCapturer.stopCapture();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        videoCapturer.startCapture(width, height, 25);
    }

    @Override
    public void onStartRecord() {
        cameraRecorder = new CameraRecorder();
        cameraRecorder.init(eglBase);
        //摄像机的宽高和实际视频宽高刚好是相反的
        cameraRecorder.startRecord(cameraPreviewSize.height, cameraPreviewSize.width, 6000000);
        shutterButton.setEnableEncoder(true);
    }

    @Override
    public void onStopRecord() {
        cameraRecorder.stopRecord();
        cameraRecorder = null;
    }

    @Override
    public void onProgressOver() {

    }
}
