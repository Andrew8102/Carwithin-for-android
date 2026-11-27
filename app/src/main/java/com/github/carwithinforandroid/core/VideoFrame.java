package com.github.carwithinforandroid.core;

/** 一帧 Annex-B H.264 接入单元。 */
public final class VideoFrame {
    public final byte[] accessUnit;
    public final long ptsMs;
    public final boolean keyframe;

    public VideoFrame(byte[] accessUnit, long ptsMs, boolean keyframe) {
        this.accessUnit = accessUnit;
        this.ptsMs = ptsMs;
        this.keyframe = keyframe;
    }
}
