package org.schabi.newpipe.enhancements.groupsort

import io.reactivex.rxjava3.core.Flowable

/**
 * Combines a stream of group lists in manual order with the stream of the setting.
 * Kotlin and RxJava only, no Android.
 */
object GroupSortStreams {

    /**
     * Each group list sorted by the latest mode. Nothing is emitted until both sources have
     * emitted; then one emission per group list and one per changed mode. Errors pass through.
     */
    fun <T : Any> sorted(
        groups: Flowable<List<T>>,
        modes: Flowable<GroupSortMode>,
        nameOf: (T) -> String
    ): Flowable<List<T>> =
        Flowable.combineLatest(groups, modes.distinctUntilChanged()) { list, mode ->
            GroupSorter.sort(list, mode, nameOf)
        }
}
