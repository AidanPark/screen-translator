package com.galaxy.airviewdictionary.ui.screen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import timber.log.Timber


open class AVDActivity : ComponentActivity() {

    protected open val TAG = javaClass.simpleName

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15(SDK 35)+ 에서는 edge-to-edge 가 강제되므로,
        // 이전 버전에서도 동일한 동작이 되도록 명시적으로 활성화한다. (Play Console 권장 조치)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Timber.tag(TAG).i("#### onCreate ####")
    }

    override fun onResume() {
        super.onResume()
        Timber.tag(TAG).i("#### onResume ####")
    }

    override fun onPause() {
        super.onPause()
        Timber.tag(TAG).i("#### onPause ####")
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.tag(TAG).i("#### onDestroy ####")
    }
}
