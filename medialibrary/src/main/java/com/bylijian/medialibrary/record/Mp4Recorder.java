package com.bylijian.medialibrary.record;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.view.Surface;


import com.bylijian.medialibrary.record.audio.AndroidAudioSource;
import com.bylijian.medialibrary.record.audio.AudioConfig;
import com.bylijian.medialibrary.record.audio.AudioSource;
import com.bylijian.medialibrary.record.audio.AudioSourceObserver;
import com.bylijian.medialibrary.record.audio.AudioUtil;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingDeque;

public class Mp4Recorder implements AudioSourceObserver {
    private static final String TAG = "Mp4Recorder";

    private AudioSource audioSource;
    private LinkedBlockingDeque<ByteBuffer> audioDatas;

    private Surface inputSurface;
    private MediaCodec videoEncoder;
    private MediaCodec audioEncoder;
    private MediaCodec.Callback videoEncoderCallback;
    private MediaCodec.Callback audioEncoderCallback;

    private MediaMuxer muxer;
    private HandlerThread muxThread;
    private Handler muxHandler;
    private int videoTrackIndex = -1;
    private int audioTrackIndex = -1;
    private boolean muxerStarted;

    private boolean recordStarted = false;

    public Mp4Recorder() {
        initEncoderCallback();
    }

    private void initEncoderCallback() {
        videoEncoderCallback = new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                Log.d(TAG, "video MediaCodec Input Buffer Avail thead=" + Thread.currentThread().getName());
            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                Log.d(TAG, "video MediaCodec onOutputBufferAvailable() index=" + index + "thread=" + Thread.currentThread().getName());
                ByteBuffer encodedData = videoEncoder.getOutputBuffer(index);
                if (encodedData == null) {
                    throw new RuntimeException("couldn't fetch buffer at index " + index);
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    info.size = 0;
                }
                if (info.size != 0) {
                    if (muxerStarted && muxer != null) {
                        encodedData.position(info.offset);
                        encodedData.limit(info.offset + info.size);
                        muxer.writeSampleData(videoTrackIndex, encodedData, info);
                    }
                }
                videoEncoder.releaseOutputBuffer(index, false);
            }

            @Override
            public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                Log.e(TAG, "MediaCodec " + codec.getName() + " onError:", e);
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                Log.d(TAG, "MediaCodec Output Format changed");
                if (videoTrackIndex >= 0) {
                    throw new RuntimeException("format changed twice");
                }
                videoTrackIndex = muxer.addTrack(videoEncoder.getOutputFormat());
                if (!muxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0 && muxer != null) {
                    Log.d(TAG, "start muxer");
                    muxer.start();
                    muxerStarted = true;
                }
            }
        };
        audioEncoderCallback = new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                long presentationTimeUs = System.nanoTime() / 1000;
                Log.v(TAG, "audioEncoder onInputBufferAvailable() index=" + index + " thread=" + Thread.currentThread().getName());
                if (!recordStarted) {
                    audioEncoder.queueInputBuffer(index, 0, 0,
                            presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    Log.d(TAG, "audioEncoder BUFFER_FLAG_END_OF_STREAM");
                    return;
                }
                if (audioDatas != null && audioDatas.size() > 0) {
                    ByteBuffer inputBuffer = codec.getInputBuffer(index);
                    ByteBuffer pop = audioDatas.poll();
                    inputBuffer.clear();
                    inputBuffer.limit(pop.capacity());
                    inputBuffer.put(pop);

                    //Log.d(TAG, "audioEncoder presentationTime=" + presentationTimeUs);
                    codec.queueInputBuffer(index, 0, pop.capacity(), presentationTimeUs, 0);
                } else {
                    codec.queueInputBuffer(index, 0, 0, presentationTimeUs, 0);
                }

            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                Log.v(TAG, "audioEncoder onOutputBufferAvailable() thread=" + Thread.currentThread().getName());
                ByteBuffer encodedData = audioEncoder.getOutputBuffer(index);
                if (encodedData == null) {
                    throw new RuntimeException("couldn't fetch buffer at index " + index);
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    info.size = 0;
                }
                if (info.size != 0) {
                    if (muxerStarted && muxer != null) {
                        encodedData.position(info.offset);
                        encodedData.limit(info.offset + info.size);
                        info.presentationTimeUs = System.nanoTime() / 1000;
                        muxer.writeSampleData(audioTrackIndex, encodedData, info);
                        //Log.d(TAG, " muxer.writeSampleData() presentationTimeUs=" + info.presentationTimeUs + "size=" + info.size);
                    }
                }
                audioEncoder.releaseOutputBuffer(index, false);
            }

            @Override
            public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                Log.e(TAG, "audioEncoder onError()", e);
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                //Log.d(TAG, "audioEncoder onOutputFormatChanged()");
                audioTrackIndex = muxer.addTrack(audioEncoder.getOutputFormat());
                if (!muxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0 && muxer != null) {
                    Log.d(TAG, "start muxer");
                    muxer.start();
                    muxerStarted = true;
                }
            }
        };
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public Surface prepareVideoEncoder(int width, int height, int bitrate) {
        if (videoEncoder != null) {
            Log.e(TAG, "prepareVideoEncoder()");
        }
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        int frameRate = 25; // 25 fps

        // Set some required properties. The media codec may fail if these aren't defined.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate); // 6Mbps
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_CAPTURE_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / frameRate);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // 1 seconds between I-frames

        // Create a MediaCodec encoder and configure it. Get a Surface we can use for recording into.
        try {
            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = videoEncoder.createInputSurface();

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || muxHandler == null) {
                videoEncoder.setCallback(videoEncoderCallback);
            } else {
                videoEncoder.setCallback(videoEncoderCallback, muxHandler);
            }

        } catch (IOException e) {
            Log.e(TAG, "prepareVideoEncoder()", e);
            releaseEncoders();
        }
        return inputSurface;
    }

    private void prepareAudioEncoder() {
        try {
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                    AudioConfig.SAMPLE_RATE, 1);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AudioUtil.getPCMBufferSize(AudioConfig.SAMPLE_RATE, AudioConfig.FRAME_COUNT));
            format.setInteger(MediaFormat.KEY_SAMPLE_RATE, AudioConfig.SAMPLE_RATE);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
            audioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || muxHandler == null) {
                audioEncoder.setCallback(audioEncoderCallback);
            } else {
                audioEncoder.setCallback(audioEncoderCallback, muxHandler);
            }
        } catch (IOException e) {
            Log.e(TAG, "prepareAudioEncoder()", e);
            e.printStackTrace();
        }

    }

    public void startRecord(File file) {
        if (file == null) {
            return;
        }
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        try {
            audioDatas = new LinkedBlockingDeque<>();
            muxThread = new HandlerThread("Android-mp4-mux");
            muxThread.start();
            muxHandler = new Handler(muxThread.getLooper());

            recordStarted = true;
            muxer = new MediaMuxer(file.getCanonicalPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            prepareAudioEncoder();
            audioSource = new AndroidAudioSource();
            audioSource.init(this);
            audioSource.start();
            videoEncoder.start();
            audioEncoder.start();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public void stopRecord() {
        recordStarted = false;
        releaseMuxer();
        releaseEncoders();
        releaseAudioSource();
        //最后的时候结束线程，否则其他回调在一个结束的线程会奔溃
        closeThread();
    }

    private void releaseAudioSource() {
        if (audioSource != null) {
            audioSource.stop();
            audioSource.release();
            audioSource = null;
        }
    }

    private void releaseMuxer() {
        if (muxer != null) {
            muxerStarted = false;
            if (muxerStarted) {
                muxer.stop();
                muxer.release();
            }
            muxer = null;
            Log.d(TAG, "release muxer");
        }
        videoTrackIndex = -1;
        audioTrackIndex = -1;
    }

    private void closeThread() {
        if (muxThread != null) {
            muxHandler.removeCallbacksAndMessages(null);
            muxThread.quitSafely();
            muxHandler = null;
            muxThread = null;
        }
    }

    private void releaseEncoders() {
        if (videoEncoder != null) {
            videoEncoder.stop();
            videoEncoder.release();
            videoEncoder = null;
            Log.d(TAG, "release videoEncoder");
        }
        if (audioEncoder != null) {
            audioEncoder.stop();
            audioEncoder.release();
            audioEncoder = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
            Log.d(TAG, "release inputSurface");
        }
    }


    @Override
    public void onAudio(ByteBuffer byteBuffer) {
        if (byteBuffer != null && audioDatas != null) {
            Log.d(TAG, "onAudio()");
            audioDatas.offer(byteBuffer);
        }
    }


    /**
     * 添加ADTS头，如果要与视频流合并就不用添加，单独AAC文件就需要添加，否则无法正常播放
     * 这个用于单独保存aac到一个文件，测试，目前不用
     *
     * @param packet    之前创建的字节数组，保存头和编码后音频数据
     * @param packetLen 字节数组总长度
     */
    private void addADTStoPacket(byte[] packet, int packetLen) {
        int profile = 2;  //AAC LC
        //39=MediaCodecInfo.CodecProfileLevel.AACObjectELD;
        int freqIdx = 4;  //44.1KHz
        int chanCfg = 1;  //CPE

        // fill in ADTS data
        packet[0] = (byte) 0xFF;
        packet[1] = (byte) 0xF9;
        packet[2] = (byte) (((profile - 1) << 6) + (freqIdx << 2) + (chanCfg >> 2));
        packet[3] = (byte) (((chanCfg & 3) << 6) + (packetLen >> 11));
        packet[4] = (byte) ((packetLen & 0x7FF) >> 3);
        packet[5] = (byte) (((packetLen & 7) << 5) + 0x1F);
        packet[6] = (byte) 0xFC;
    }

}
