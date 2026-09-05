package com.universalmusic.player.data.auth

import com.universalmusic.player.domain.model.ProviderId
import kotlinx.serialization.Serializable

@Serializable
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtEpochMs: Long? = null,
    val scopes: List<String> = emptyList(),
    val extra: Map<String, String> = emptyMap(),
) {
    fun isExpired(nowMs: Long, skewMs: Long = 30_000): Boolean {
        val expiresAt = expiresAtEpochMs ?: return false
        return nowMs >= expiresAt - skewMs
    }
}

interface TokenStore {
    suspend fun read(provider: ProviderId): AuthTokens?
    suspend fun write(provider: ProviderId, tokens: AuthTokens)
    suspend fun clear(provider: ProviderId)
}
