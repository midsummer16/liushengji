package com.voiceclone.app.data.api

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkClient {

    private const val PREFS_NAME = "voice_clone_prefs"
    private const val KEY_BASE_URL = "base_url"
    private const val DEFAULT_BASE_URL = ""

    @Volatile private var baseUrl: String = DEFAULT_BASE_URL
    @Volatile private var initialized: Boolean = false
    private var apiService: ApiService? = null

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_BASE_URL, null)
        baseUrl = if (!saved.isNullOrBlank()) saved else DEFAULT_BASE_URL
        initialized = true
    }

    fun getBaseUrl(): String = baseUrl

    fun setBaseUrl(url: String, context: Context) {
        init(context)
        var formatted = url.trim()
        if (!formatted.startsWith("http://") && !formatted.startsWith("https://")) {
            formatted = "http://$formatted"
        }
        if (!formatted.endsWith("/")) {
            formatted = "$formatted/"
        }
        baseUrl = formatted
        apiService = null // Re-create client with new URL

        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_BASE_URL, formatted).apply()
    }

    fun getApiService(context: Context): ApiService {
        init(context)
        if (apiService == null) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            }

            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS) // Long timeout for streaming TTS
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            apiService = retrofit.create(ApiService::class.java)
        }
        return apiService!!
    }
}
