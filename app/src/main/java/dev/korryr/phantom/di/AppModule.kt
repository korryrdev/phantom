package dev.korryr.phantom.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.korryr.phantom.ai.GroqRepository
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            // FIX: Was 1s — too aggressive. On a throttled connection (Safaricom FUP, etc.)
            // the TCP handshake to api.groq.com can take 1-2s, causing immediate failure.
            // 3s is a better balance between fast failure and real-world network tolerance.
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)   // Streaming: chunks arrive over time
            .writeTimeout(3, TimeUnit.SECONDS)
            // Keep TCP connections alive — skip handshake on repeat requests
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .build()
    }

    @Provides
    @Singleton
    fun provideGroqRepository(client: OkHttpClient): GroqRepository {
        return GroqRepository(client)
    }
}
