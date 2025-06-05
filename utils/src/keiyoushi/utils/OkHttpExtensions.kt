package keiyoushi.utils

import okhttp3.Headers
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString

/**
 * Empty [okhttp3] headers used by the source.
 *
 * Provides backward compatibility with the older internal `okhttp3.internal.commonEmptyHeaders`
 * by lazily returning an instance equivalent to [okhttp3.Headers.EMPTY].
 *
 * If app is still using [okhttp3] (ver 5.0.0-alpha.14) then even if extensions
 * are updated to new API, the call would fail to look for actual instance.
 *
 * WARNING: Keep using this if various apps are still on older [okhttp3]
 */
@Deprecated("Use newer API `Headers.EMPTY` instead", replaceWith = ReplaceWith("Headers.EMPTY"))
val commonEmptyHeaders by lazy { Headers.Builder().build() }

/**
 * Empty [okhttp3] request body used by the source.
 *
 * Provides backward compatibility with the older internal `okhttp3.internal.commonEmptyRequestBody`
 * by lazily returning an instance equivalent to [okhttp3.RequestBody.EMPTY].
 *
 * If app is still using [okhttp3] (ver 5.0.0-alpha.14) then even if extensions
 * are updated to new API, the call would fail to look for actual instance.
 *
 * WARNING: Keep using this if various apps are still on older [okhttp3]
 */
@Deprecated("Use newer API `RequestBody.EMPTY` instead", replaceWith = ReplaceWith("RequestBody.EMPTY"))
val commonEmptyRequestBody by lazy { ByteString.EMPTY.toRequestBody() }
