package com.universalmusic.player.domain.model

enum class RepeatMode {
    OFF,
    ALL,
    ONE,
}

data class QueueItem(
    val id: String,
    val track: Track,
)

data class PlaybackQueue(
    val items: List<QueueItem> = emptyList(),
    val currentIndex: Int = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val shuffleOrder: List<Int> = emptyList(),
) {
    val current: QueueItem?
        get() = items.getOrNull(effectiveIndex)

    val upcoming: List<QueueItem>
        get() {
            if (items.isEmpty()) return emptyList()
            val order = playbackOrder()
            val currentPos = order.indexOf(effectiveIndex).takeIf { it >= 0 } ?: return emptyList()
            return order.drop(currentPos + 1).mapNotNull { items.getOrNull(it) }
        }

    val history: List<QueueItem>
        get() {
            if (items.isEmpty()) return emptyList()
            val order = playbackOrder()
            val currentPos = order.indexOf(effectiveIndex).takeIf { it >= 0 } ?: return emptyList()
            return order.take(currentPos).mapNotNull { items.getOrNull(it) }
        }

    private val effectiveIndex: Int
        get() = currentIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))

    fun playbackOrder(): List<Int> {
        if (items.isEmpty()) return emptyList()
        return if (shuffle && shuffleOrder.size == items.size) {
            shuffleOrder
        } else {
            items.indices.toList()
        }
    }
}
