package online.moondev.orbit

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import online.moondev.orbit.data.local.PreferencesManager
import java.util.concurrent.TimeUnit

class MoonOrbitApp : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        val prefs = PreferencesManager(this)
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val token = runBlocking { prefs.sessionToken.firstOrNull() }
                val builder = original.newBuilder()
                    .addHeader("X-App-Version-Code", "1")
                if (token != null && original.header("Authorization") == null) {
                    builder.addHeader("Authorization", "Bearer $token")
                }
                chain.proceed(builder.build())
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
