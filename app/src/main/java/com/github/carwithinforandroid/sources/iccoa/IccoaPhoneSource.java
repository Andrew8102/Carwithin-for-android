package com.github.carwithinforandroid.sources.iccoa;

import android.content.Context;

import com.github.carwithinforandroid.core.PhoneVideoSource;

/**
 * 阶段二：ICCOA CarLink 手机来源，覆盖小米 CarWith / OPPO Car+ / vivo Jovi InCar。
 *
 * 协议实现是第三方专有 icar-sdk.dex（约 7 MB），公开仓库只有编译桩，
 * 详见 docs/ICCOA-STATUS.md。拿到合法 SDK 后在此实现 PhoneLink 槽位。
 */
public final class IccoaPhoneSource implements PhoneVideoSource {

    public static final String SDK_DEX_PATH = "app/libs/icar-sdk.dex";

    @Override
    public void start(Context context, Callback callback) {
        callback.onState("ICCOA_SDK_MISSING");
        callback.onError(new IllegalStateException("缺少 " + SDK_DEX_PATH));
    }

    @Override
    public void stop() {
    }

    @Override
    public String status() {
        return "SDK_MISSING";
    }
}
