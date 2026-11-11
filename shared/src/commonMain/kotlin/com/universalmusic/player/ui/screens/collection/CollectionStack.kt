package com.universalmusic.player.ui.screens

import com.universalmusic.player.ui.navigation.AppDestination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An open page; [id] is unique for the app's life, so its scroll is saved under its own key. */
internal class CollectionPage(
    val id: Long,
    val ref: CollectionRef,
    val presenter: CollectionPresenter,
    private val job: Job,
) {
    internal fun close() = job.cancel()
}

/** Builds the presenter for a page; its loads run in [scope] and stop when the page closes. */
internal fun interface CollectionPages {
    fun open(ref: CollectionRef, scope: CoroutineScope): CollectionPresenter
}

/**
 * The pages a tab has opened over itself, newest last. Back closes the top page and returns to the
 * one under it (or the tab), whose scroll was kept.
 */
internal class CollectionStack(
    private val scope: CoroutineScope,
    private val pages: CollectionPages,
    private val nextId: () -> Long,
) {
    private val mutablePages = MutableStateFlow<List<CollectionPage>>(emptyList())
    val stack: StateFlow<List<CollectionPage>> = mutablePages.asStateFlow()
    val top: CollectionPage? get() = stack.value.lastOrNull()

    /** Opens [ref] over the current page; nothing happens when it is already the page on top. */
    fun push(ref: CollectionRef) {
        if (top?.ref?.key == ref.key) return
        val job = SupervisorJob(scope.coroutineContext[Job])
        val presenter = pages.open(ref, CoroutineScope(scope.coroutineContext + job))
        mutablePages.value = stack.value + CollectionPage(nextId(), ref, presenter, job)
    }

    /** Opens [ref] as the only page, as tapping a result or a Library row does. */
    fun openFresh(ref: CollectionRef) {
        if (stack.value.size == 1 && top?.ref?.key == ref.key) return
        clear()
        push(ref)
    }

    /** Closes the top page; false when none was open. Cancels its loads and a pending Play. */
    fun back(): Boolean {
        val current = stack.value
        val page = current.lastOrNull() ?: return false
        page.close()
        mutablePages.value = current.dropLast(1)
        return true
    }

    fun clear() {
        stack.value.forEach(CollectionPage::close)
        mutablePages.value = emptyList()
    }
}

/** One [CollectionStack] per tab, so each tab keeps the pages it opened while another is shown. */
internal class CollectionNavigator(scope: CoroutineScope, pages: CollectionPages) {
    private var lastId = 0L
    private val stacks = AppDestination.entries.associateWith {
        CollectionStack(scope, pages) { ++lastId }
    }

    /** Type-to-search always reveals Library's search, even if that tab was left on a page. */
    fun focusLibrarySearch(navigate: (AppDestination) -> Unit) {
        stack(AppDestination.Library).clear()
        navigate(AppDestination.Library)
    }

    fun stack(destination: AppDestination): CollectionStack = stacks.getValue(destination)
}
