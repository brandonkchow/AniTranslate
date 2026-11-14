package com.example.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClientProvider {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(75, TimeUnit.SECONDS)
            .writeTimeout(75, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Local-provider variant (CUSTOM / WORKSTATION slots): a CPU- or LAN-hosted
     * model can take minutes per page, far past the cloud-tuned 90s call cap.
     * Measured 2026-11-13 on wuwei: one full-page detect call took 25 minutes
     * server-side while an emulator shared the host, so 20 minutes still caps
     * below local reality. Cloud providers keep the short timeout so a hung
     * API fails fast.
     */
    val localClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(60, TimeUnit.MINUTES)
            .writeTimeout(60, TimeUnit.MINUTES)
            .callTimeout(60, TimeUnit.MINUTES)
            .build()
    }
}
