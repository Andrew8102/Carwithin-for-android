package com.github.carwithinforandroid.sources.hicar;

import android.content.Context;

import com.github.carwithinforandroid.core.PhoneVideoSource;

/** 阶段三预留：华为 HiCar 不复用 ICCOA，待前两阶段稳定后再评估。 */
public final class HiCarPhoneSource implements PhoneVideoSource {

    @Override
    public void start(Context context, Callback callback) {
        callback.onState("HICAR_RESERVED");
    }

    @Override
    public void stop() {
    }

    @Override
    public String status() {
        return "RESERVED";
    }
}
