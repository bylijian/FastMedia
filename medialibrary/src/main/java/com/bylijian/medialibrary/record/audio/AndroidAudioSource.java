package com.bylijian.medialibrary.record.audio;

import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import java.nio.ByteBuffer;


public class AndroidAudioSource implements AudioSource, AudioRecord.OnRecordPositionUpdateListener {
    private static final String TAG = "AndroidAudioSource";

    private HandlerThread thread;
    private Handler handler;
    private AudioRecord audioRecord;
    private AudioSourceObserver audioSourceObserver;
    private final int pcmBufferSize;

    public AndroidAudioSource() {
        pcmBufferSize = AudioUtil.getPCMBufferSize(AudioConfig.SAMPLE_RATE, AudioConfig.FRAME_COUNT);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, AudioConfig.SAMPLE_RATE, AudioConfig.CHANNEL_MODE, AudioConfig.PCM_BIT, pcmBufferSize);
        audioRecord.setPositionNotificationPeriod(AudioConfig.FRAME_COUNT);
    }

    @Override
    public void init(AudioSourceObserver observer) {
        audioSourceObserver = observer;
    }

    @Override
    public void start() {
        Log.d(TAG, "start()");
        thread = new HandlerThread("Android-audio-source");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            }
        });
        audioRecord.setRecordPositionUpdateListener(this, handler);
        audioRecord.startRecording();
    }

    @Override
    public void stop() {
        Log.d(TAG, "stop()");
        audioRecord.stop();
        thread.quitSafely();
    }

    @Override
    public void release() {
        Log.d(TAG, "release()");
        audioRecord.release();
    }

    @Override
    public void onMarkerReached(AudioRecord recorder) {
        Log.d(TAG, "onMarkerReached()");
    }

    @Override
    public void onPeriodicNotification(AudioRecord recorder) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(AudioUtil.getPCMBufferSize(AudioConfig.SAMPLE_RATE, AudioConfig.FRAME_COUNT));
        int readSize = audioRecord.read(byteBuffer, byteBuffer.capacity());
        Log.v(TAG, "onPeriodicNotification()" + audioRecord + "readSize=" + readSize);
        if (readSize >= 0) {
            if (audioSourceObserver != null) {
                audioSourceObserver.onAudio(byteBuffer);
            }
        }
    }
}
