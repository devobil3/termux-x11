package com.termux.x11.utils;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class KeyInterceptorService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        return KeyInterceptor.getBinder();
    }
}
