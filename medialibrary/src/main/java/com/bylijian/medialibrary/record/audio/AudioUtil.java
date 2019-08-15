package com.bylijian.medialibrary.record.audio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.util.Log;


/**
 * @author lij004
 * @date 2018/6/29
 */
public class AudioUtil {
    private static final String TAG = "AudioUtil";

    /**
     * AudioRecord的BufferSize需要综合考虑
     * 使用AudioRecord.getMinBufferSize()获取最小一个值，
     * 然后这个值需要是miniFramesSize整数倍
     *
     * @return
     */
    public static int getPCMBufferSize(int sampleRate, int framesPeriod) {
        //使用Android官方的api计算需要的最小Buffer长度,这个值的单位是byte
        int bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        //我们计算的最小的长度是，采样点*每个采样点的长度
        int miniFramesSize = framesPeriod * 2;

        if (bufferSize < miniFramesSize) {
            bufferSize = miniFramesSize * 5;
        } else {
            if (bufferSize % miniFramesSize != 0) {
                bufferSize = bufferSize + (miniFramesSize - bufferSize % miniFramesSize);
            }
        }
        return bufferSize;
    }

    /**
     * 计算声音分贝值
     *
     * @param buffer
     * @param size
     * @return
     */
    public static double volume(short buffer[], int size) {
        long v = 0;
        if (buffer == null || size <= 0 || size > buffer.length) {
            return 0;
        }
        // 将 buffer 内容取出，进行平方和运算
        Log.d(TAG, "分贝值buffer.length:" + buffer.length);
        for (int i = 0; i < size; i++) {
            v += buffer[i] * buffer[i];
        }
        // 平方和除以数据总长度，得到音量大小。
        double mean = v / (double) size;
        Log.d(TAG, "mean:" + mean);
        if (v == 0) {
        }
        Log.d(TAG, "分贝值v:" + v);
        double volume = 10 * Math.log10(mean);
        Log.d(TAG, "分贝值volume:" + volume);
        // ToastUtils.showDebugToast("分贝值:"+ volume);
        if (volume == 0) {
            Log.d(TAG, "音量为0,请检查录音权限");
        }
        return volume;
    }
}
