package com.bylijian.medialibrary.record.audio;

import android.media.AudioFormat;

/**
 * 音频录制、编码参数配置
 *
 * @author lij004
 * @date 2018/6/29
 */
public class AudioConfig {
    /**
     * 默认使用44100的音频采样率，44100，
     * 注意这个数值会影响录制的原始PCM音频数据大小
     */
    public static final int SAMPLE_RATE = 44100;

    /**
     * 自定义 每441采样点作为一个周期，AudioRecord每一个周期，会回调通知使用方需要取出原始数据
     * 如果采样率是44100，也即是一秒钟采集44100个采样点，那么441个采样点的回调时间就是1/100=0.01s
     * 也就是10ms回调异常
     */
    public static final int FRAME_COUNT = 441;

    /**
     * 默认的采用单声道也就是AudioFormat.CHANNEL_IN_MONO
     * 经过测试单声道录制出来的声音可以满足一般需求,处理简单，兼容性也最好
     */
    public static final int CHANNEL_MODE = AudioFormat.CHANNEL_IN_MONO;

    /**
     * AudioFormat.CHANNEL_IN_MONO 对应从声道个数自然是1
     */
    public static final int CHANNEL_COUNT = 1;

    /**
     * 每个音频采样点的长度是16bit，也即是2Byte
     */
    public static final int PCM_BIT = AudioFormat.ENCODING_PCM_16BIT;

}
