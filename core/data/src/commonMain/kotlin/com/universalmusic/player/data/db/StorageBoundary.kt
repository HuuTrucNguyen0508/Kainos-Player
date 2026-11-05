package com.universalmusic.player.data.db

import com.universalmusic.player.domain.failure.AppFailures
import com.universalmusic.player.domain.failure.StorageArea
import com.universalmusic.player.domain.failure.StorageFailure
import kotlinx.coroutines.CancellationException

internal inline fun <T> storageRead(area: StorageArea, read: () -> T): T = try {
    read()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    throw failure as? StorageFailure ?: StorageFailure(area, failure)
}

internal inline fun <T> storageWrite(area: StorageArea, write: () -> T): T =
    storageRead(area, write).also { AppFailures.recovered(area) }

/** A strict read is part of a write, which then fails as a whole in its own area. */
internal inline fun <T> readStorageRow(strict: Boolean, read: () -> T): T? = try {
    read()
} catch (failure: Exception) {
    if (strict) throw failure
    AppFailures.report(StorageFailure(StorageArea.CORRUPT_ROWS, failure))
    null
}
