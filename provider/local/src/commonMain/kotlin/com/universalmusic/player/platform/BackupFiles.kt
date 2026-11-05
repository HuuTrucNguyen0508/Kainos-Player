package com.universalmusic.player.platform

/** A backup larger than this is refused unread; real ones are a few megabytes at most. */
const val MAX_BACKUP_FILE_BYTES = 32 * 1024 * 1024

/** Thrown when a picked backup file cannot be read or written; [message] is shown to the user. */
class BackupFileAccessException(message: String) : Exception(message)

/**
 * Asks where to save a backup with the platform save picker and writes [content] there.
 * Returns the saved file's name, or null when the user cancelled.
 */
expect suspend fun saveBackupFile(suggestedName: String, content: String): String?

/** Asks for a backup file with the platform open picker and returns its text, or null when cancelled. */
expect suspend fun openBackupFile(): String?
