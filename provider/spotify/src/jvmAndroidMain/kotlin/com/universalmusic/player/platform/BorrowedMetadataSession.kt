package com.universalmusic.player.platform

import java.io.IOException
import kotlinx.coroutines.CancellationException

internal class ReceiverSessionReplaced(cause: Throwable? = null) :
    IOException("the playback receiver was rebuilt during the call", cause)

internal suspend fun <S : Any, T> readBorrowedMetadata(
    session: S,
    current: () -> S?,
    block: suspend (S) -> T,
): T = try {
    val result = block(session)
    if (current() !== session) throw ReceiverSessionReplaced()
    result
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    if (current() !== session) throw ReceiverSessionReplaced(failure)
    throw failure
}
