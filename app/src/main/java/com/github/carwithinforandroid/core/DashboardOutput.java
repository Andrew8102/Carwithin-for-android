package com.github.carwithinforandroid.core;

import android.content.Context;

import java.io.IOException;

/** 仪表侧输出的统一入口。极核 AE4 MAX2+ 6.2 寸是第一个实现。 */
public interface DashboardOutput {

    void start(Context context, int width, int height, int fps) throws IOException;

    void onFrame(VideoFrame frame) throws IOException;

    void stop();

    String status();
}
