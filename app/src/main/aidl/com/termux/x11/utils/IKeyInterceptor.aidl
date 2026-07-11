package com.termux.x11.utils;

import com.termux.x11.utils.IKeyCallback;

interface IKeyInterceptor {
    void registerCallback(IKeyCallback callback);
    void unregisterCallback(IKeyCallback callback);
    void requestRecheck();
}
