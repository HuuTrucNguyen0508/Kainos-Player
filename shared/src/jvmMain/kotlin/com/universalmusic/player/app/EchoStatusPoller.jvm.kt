package com.universalmusic.player.app

/** The gateway's loopback status port; `KAINOS_ECHO_STATUS_URL` points elsewhere (base URL, no path). */
internal actual fun echoStatusBaseUrl(): String? =
    System.getenv("KAINOS_ECHO_STATUS_URL")?.trim()?.takeIf { it.isNotEmpty() } ?: "http://127.0.0.1:8788"
