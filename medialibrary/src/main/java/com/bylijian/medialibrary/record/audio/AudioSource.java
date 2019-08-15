package com.bylijian.medialibrary.record.audio;

public interface AudioSource {

    void init(AudioSourceObserver observer);

    void start();

    void stop();

    void release();

}
