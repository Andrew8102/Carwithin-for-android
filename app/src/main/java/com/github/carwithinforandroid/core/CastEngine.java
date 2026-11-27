package com.github.carwithinforandroid.core;

import android.content.Context;

import java.util.concurrent.atomic.AtomicReference;

/** 把手机来源的视频帧桥接到仪表输出。 */
public final class CastEngine implements PhoneVideoSource.Callback {

    private final AtomicReference<String> state = new AtomicReference<>("IDLE");
    private PhoneVideoSource source;
    private DashboardOutput output;

    public void attach(PhoneVideoSource source, DashboardOutput output) {
        this.source = source;
        this.output = output;
    }

    public void start(Context context) {
        if (source == null || output == null) {
            state.set("NO_SOURCE_OR_OUTPUT");
            return;
        }
        state.set("STARTING");
        try {
            output.start(context, 1024, 480, 25);
            source.start(context, this);
            state.set("RUNNING");
        } catch (Exception e) {
            state.set("ERROR: " + e.getMessage());
            onError(e);
        }
    }

    public void stop() {
        state.set("STOPPING");
        if (source != null) source.stop();
        if (output != null) output.stop();
        state.set("IDLE");
    }

    public String status() {
        return state.get();
    }

    @Override
    public void onFrame(VideoFrame frame) {
        if (output == null) return;
        try {
            output.onFrame(frame);
        } catch (Exception e) {
            state.set("FRAME_ERROR: " + e.getMessage());
        }
    }

    @Override
    public void onState(String s) {
        state.set(s);
    }

    @Override
    public void onError(Throwable error) {
        state.set("ERROR: " + error.getMessage());
    }
}
