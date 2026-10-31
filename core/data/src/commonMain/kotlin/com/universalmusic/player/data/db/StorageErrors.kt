package com.universalmusic.player.data.db

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object StorageErrors {
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()

    fun report() {
        mutableMessage.value = "Some saved data could not be loaded or saved. Restart the app to retry. Your database has been kept."
    }
}

internal inline fun <T> readStorageRow(strict: Boolean, read: () -> T): T? = try {
    read()
} catch (failure: Exception) {
    StorageErrors.report()
    if (strict) throw failure
    null
}
