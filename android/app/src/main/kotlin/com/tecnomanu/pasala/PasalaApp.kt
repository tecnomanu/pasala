package com.tecnomanu.pasala

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import com.tecnomanu.pasala.core.Receiver
import com.tecnomanu.pasala.data.Store
import com.tecnomanu.pasala.service.Notifications

class PasalaApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Receiver.init(this)
        Notifications.createChannels(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
}
