package com.bylijian.medialibrary.record.audio;

import java.nio.ByteBuffer;

public interface AudioSourceObserver {

    /**
     * 采集到的音频数据回调
     *
     * @param byteBuffer 原始的pcm音频数据
     */
    void onAudio(ByteBuffer byteBuffer);

}
