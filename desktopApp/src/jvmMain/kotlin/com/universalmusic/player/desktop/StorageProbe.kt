package com.universalmusic.player.desktop

import com.universalmusic.player.platform.createUserLibraryStore
import kotlinx.coroutines.runBlocking

/** Diagnostic only: isolate native initialization from the guarded app database open. */
internal fun runStorageProbe() = runBlocking {
    fun mark(name: String) = println("PERF\t$name\t${System.nanoTime()}")
    val initialize = Class.forName("org.sqlite.SQLiteJDBCLoader").getMethod("initialize")
    mark("sqlite_initialize_begin")
    check(initialize.invoke(null) == true)
    mark("sqlite_initialize_end")
    val store = createUserLibraryStore()
    mark("database_first_read_begin")
    store.read()
    mark("database_first_read_end")
    mark("database_second_read_begin")
    store.read()
    mark("database_second_read_end")
}
