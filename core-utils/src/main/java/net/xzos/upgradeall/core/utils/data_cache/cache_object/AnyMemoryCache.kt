package net.xzos.upgradeall.core.utils.data_cache.cache_object

import net.xzos.upgradeall.core.utils.data_cache.CacheConfig
import net.xzos.upgradeall.core.utils.log.Log
import net.xzos.upgradeall.core.utils.log.ObjectTag
import net.xzos.upgradeall.core.utils.log.ObjectTag.Companion.core
import java.time.Instant

class AnyMemoryCache<T>(
    key: String,
    config: CacheConfig,
    var saveMode: SaveMode,
) : BaseCache<T>(key) {

    private var any: T? = null
        set(value) {
            if (saveMode == SaveMode.DISK_ONLY) return
            time = Instant.now().epochSecond
            field = value
        }
    private val bytesDiskCache by lazy { config.dir?.let { BytesDiskCache(key, config) } }

    override var time: Long = 0L
        get() = bytesDiskCache?.time ?: field


    fun write(any: T?, encoder: Encoder<T>?) {
        any?.also { value ->
            this@AnyMemoryCache.any = value
            super.write(any)
            if (saveMode != SaveMode.MEMORY_ONLY)
                writeToDisk(value, encoder)
        }
    }

    private fun writeToDisk(value: T, encoder: Encoder<T>?) {
        encoder?.run {
            try {
                bytesDiskCache?.write(this.encode(value))
            } catch (e: Throwable) {
                Log.e(logObjectTag, TAG, e.stackTraceToString())
            }
        }
    }

    override fun write(any: T?) {
        this.any = any
        super.write(any)
    }

    override fun delete() = true

    fun read(encoder: Encoder<T>?, throwError: Boolean = false): T? {
        val cache = any ?: encoder?.let {
            bytesDiskCache?.read()?.let { bytes ->
                try {
                    it.decode(bytes).apply { any = this }
                } catch (e: Throwable) {
                    Log.e(logObjectTag, TAG, e.stackTraceToString())
                    null
                }
            }
        }
        if (cache == null && throwError)
            throw NoCacheError()
        else return cache as T
    }

    override fun read(): T? = any

    companion object {
        private const val TAG = "AnyMemoryCache"
        private val logObjectTag = ObjectTag(core, TAG)
    }
}