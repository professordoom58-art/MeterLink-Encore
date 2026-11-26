package com.utility.dlmsreader.dlms

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] that never swallows coroutine cancellation.
 *
 * The meter read path uses "try this object, skip it on failure" everywhere.
 * Plain [runCatching] also catches [CancellationException], so a cancelled
 * read moved on to the next object or section instead of stopping. This
 * variant rethrows cancellation and captures every other failure as before.
 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }

/** Rethrows [error] when it is (or wraps) coroutine cancellation. */
internal fun rethrowIfCancelled(error: Throwable) {
    var current: Throwable? = error
    while (current != null) {
        if (current is CancellationException) throw current
        current = current.cause
    }
}
