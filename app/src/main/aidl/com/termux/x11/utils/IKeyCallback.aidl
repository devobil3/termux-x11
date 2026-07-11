package com.termux.x11.utils;

import android.view.KeyEvent;

interface IKeyCallback {
    boolean onKeyEvent(in KeyEvent event);
    boolean shouldIntercept();
}
