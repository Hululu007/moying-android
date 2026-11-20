package com.moying.app

import android.app.Activity
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.CountDownLatch

/** Debug-only real decoder surface; excluded from the distributed release variant. */
class DebugPlaybackActivity : Activity() {
    lateinit var surfaceView: SurfaceView
        private set
    val surfaceReady = CountDownLatch(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady.countDown() }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        })
        setContentView(surfaceView)
    }
}
