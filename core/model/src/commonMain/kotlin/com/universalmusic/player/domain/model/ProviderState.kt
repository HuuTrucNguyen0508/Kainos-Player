package com.universalmusic.player.domain.model

enum class ProviderState {
    AVAILABLE,
    LOADING,
    UNAVAILABLE,
    AUTH_REQUIRED,
    RATE_LIMITED,
    NOT_CONFIGURED,
}
