package com.comicmanager.util

import android.util.Log
import io.github.jukomu.jmcomic.api.enums.ClientType
import io.github.jukomu.jmcomic.api.model.JmPhoto
import io.github.jukomu.jmcomic.core.JmComic
import io.github.jukomu.jmcomic.core.cache.CacheKey
import io.github.jukomu.jmcomic.core.client.AbstractJmClient
import io.github.jukomu.jmcomic.core.config.JmConfiguration
import java.time.Duration
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object JmClientFactory {

    val API_DOMAINS = listOf(
        "www.cdnhjk.net",
        "www.cdngwc.cc",
        "www.cdngwc.net",
        "www.cdngwc.club"
    )

    @Volatile
    private var cached: AbstractJmClient? = null

    @Volatile
    private var clientExecutor: ExecutorService? = null

    private var clientConfiguration: JmConfiguration? = null

    @Synchronized
    fun get(): AbstractJmClient {
        cached?.let { return it }
        val safeExecutor = SafeExecutorService(
            Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceAtLeast(2))
        )
        val config = JmConfiguration.Builder()
            .clientType(ClientType.API)
            .apiDomains(API_DOMAINS)
            .timeout(Duration.ofSeconds(15))
            .retryTimes(2)
            .executor(safeExecutor)
            .build()
        cached = try {
            JmComic.newApiClient(config)
        } catch (e: Exception) {
            safeExecutor.shutdownNow()
            throw e
        }
        clientExecutor = safeExecutor
        clientConfiguration = config
        return cached!!
    }

    @Synchronized
    internal fun evictChapter(client: AbstractJmClient, chapterId: String) {
        if (cached === client) {
            clientConfiguration?.cachePool?.remove(CacheKey.of(JmPhoto::class.java, chapterId))
        }
    }

    @Synchronized
    fun reset() {
        try { cached?.close() } catch (_: Exception) {}
        cached = null
        clientConfiguration = null
        clientExecutor?.shutdownNow()
        clientExecutor = null
    }

    /**
     * jmcomic 1.1.8 rethrows failures from its asynchronous initialization task.
     * Android treats that background exception as fatal, so contain it at the
     * application-provided executor boundary and let foreground calls report
     * their own failures normally.
     */
    private class SafeExecutorService(
        private val delegate: ExecutorService
    ) : AbstractExecutorService() {
        override fun execute(command: Runnable) {
            delegate.execute {
                try {
                    command.run()
                } catch (e: RuntimeException) {
                    Log.e("JmClient", "JM 后台任务失败，已阻止应用崩溃", e)
                }
            }
        }

        override fun shutdown() = delegate.shutdown()

        override fun shutdownNow(): MutableList<Runnable> = delegate.shutdownNow()

        override fun isShutdown(): Boolean = delegate.isShutdown

        override fun isTerminated(): Boolean = delegate.isTerminated

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
            delegate.awaitTermination(timeout, unit)
    }
}
