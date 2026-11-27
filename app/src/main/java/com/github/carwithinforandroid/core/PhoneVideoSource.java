package com.github.carwithinforandroid.core;

import android.content.Context;

/** 手机侧互联协议的统一入口。CarLife / ICCOA / HiCar 各自实现一个。 */
public interface PhoneVideoSource {

    void start(Context context, Callback callback);

    void stop();

    String status();

    interface Callback {
        void onFrame(VideoFrame frame);

        void onState(String state);

        void onError(Throwable error);
    }
}
