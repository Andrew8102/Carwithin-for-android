package com.github.carwithinforandroid.sources.carlife;

import android.content.Context;

import com.github.carwithinforandroid.core.PhoneVideoSource;
import com.github.carwithinforandroid.core.VideoFrame;

/**
 * 阶段一：OPPO Car+ 手机来源。
 *
 * 待移植：reference/CarNine/app/src/main/java/com/github/carnine/carlife/CarLifeLink.java
 * 需要保留七端口连接、握手、AES 解密、H.264 收流与触摸回传；
 * 把 VIDEO_INIT 画布从 848x480 改为 1024x480@25。
 */
public final class CarLifePhoneSource implements PhoneVideoSource {

    @Override
    public void start(Context context, Callback callback) {
        callback.onState("CARLIFE_NOT_IMPLEMENTED");
        callback.onError(new UnsupportedOperationException("CarLifeLink 尚未移植"));
    }

    @Override
    public void stop() {
    }

    @Override
    public String status() {
        return "STUB";
    }
}
