package com.bylijian.cameralibrary;

import android.os.Binder;
import android.os.Environment;
import android.util.Log;
import android.view.Surface;

import com.bylijian.cameralibrary.webrtc.EglBase;
import com.bylijian.cameralibrary.webrtc.GlRectDrawer;
import com.bylijian.cameralibrary.webrtc.SurfaceEglRenderer;
import com.bylijian.cameralibrary.webrtc.VideoCapturer;
import com.bylijian.cameralibrary.webrtc.VideoFrame;
import com.bylijian.medialibrary.record.Mp4Recorder;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public class CameraRecorder {
    private static final String TAG = "CameraRecorder";

    private SurfaceEglRenderer surfaceEglRenderer;
    private Mp4Recorder mp4Recorder;
    private EglBase eglBase;

    private AtomicBoolean recording = new AtomicBoolean(false);
    private OnFrameListener onFrameListener;

    private Surface outputSurface;

    public void init(EglBase eglBase) {
        this.eglBase = EglBase.create(eglBase.getEglBaseContext());
    }

    public void startRecord(int width, int height, int frameRate) {
        Log.d(TAG, "startRecord() width=" + width + "height=" + height + "frameRate=" + frameRate);
        if (recording.get()) {
            Log.e(TAG, "already recording must stop first");
            return;
        }
        recording.set(true);
        surfaceEglRenderer = new SurfaceEglRenderer("SurfaceEglRenderer");
        surfaceEglRenderer.init(eglBase.getEglBaseContext(), null, EglBase.CONFIG_RECORDABLE, new GlRectDrawer());
//            surfaceEglRenderer.setRenderMode(EglRenderer.RENDERMODE_CONTINUOUSLY);
//            surfaceEglRenderer.setFpsReduction(25);
        mp4Recorder = new Mp4Recorder();
        outputSurface = mp4Recorder.prepareVideoEncoder(width, height, frameRate);
        surfaceEglRenderer.createEglSurface(outputSurface);
        //fixme
        File outputFile = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES) + "/grafika", "ScreenRecord" +
                System.currentTimeMillis() / 1000 + ".mp4");

        mp4Recorder.startRecord(outputFile);
    }

    public void stopRecord() {
        Log.d(TAG, "stopRecord()");
        if (recording.get()) {
            recording.set(false);
            if (surfaceEglRenderer != null) {
                surfaceEglRenderer.pauseVideo();
                surfaceEglRenderer.release();
            }
            if (mp4Recorder != null) {
                mp4Recorder.stopRecord();
            }
        }
    }

    public boolean isRecording() {
        return recording.get();
    }

    public void onVideoFrame(VideoFrame frame) {
        if (surfaceEglRenderer != null) {
            surfaceEglRenderer.onFrame(frame);
        } else {
            Log.e(TAG, "onVideoFrame() but surfaceEglRenderer null ");
        }

    }

    public void setFrameListener(OnFrameListener onFrameListener) {
        this.onFrameListener = onFrameListener;
    }

    public void removeFrameListener(OnFrameListener listener) {
        if (listener == onFrameListener) {
            onFrameListener = null;
        }
    }

    public EglBase getEglBase() {
        return eglBase;
    }

    public class RecordServiceBinder extends Binder {

        public CameraRecorder getService() {
            return CameraRecorder.this;
        }
    }

    private RecordServiceBinder myBinder = new RecordServiceBinder();

    public interface OnFrameListener {
        void onFrame(VideoCapturer videoCapturer, VideoFrame videoFrame);
    }
}
