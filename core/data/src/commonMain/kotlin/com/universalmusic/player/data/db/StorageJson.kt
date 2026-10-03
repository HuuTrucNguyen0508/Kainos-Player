package com.universalmusic.player.data.db

import kotlinx.serialization.json.Json

internal val storageJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
