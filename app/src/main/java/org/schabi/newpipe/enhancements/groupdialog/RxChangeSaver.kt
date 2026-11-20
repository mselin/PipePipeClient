package org.schabi.newpipe.enhancements.groupdialog

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers

/**
 * [ChangeSaver] that writes on the io scheduler and answers on the main thread.
 *
 * A started save is never disposed: disposing would interrupt the io thread in the middle of the
 * transaction. [detach] only drops the answers that have not been delivered yet.
 */
class RxChangeSaver(private val table: GroupTable) : ChangeSaver {

    // Read and written on the main thread only.
    private var detached = false

    override fun save(changes: GroupChanges, callback: SaveCallback) {
        @Suppress("CheckResult", "UNUSED_VARIABLE")
        val neverDisposed = Completable
            .fromAction { GroupChangeWriter.apply(table, changes) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { if (!detached) callback.onResult(null) },
                { error -> if (!detached) callback.onResult(error) }
            )
    }

    /** The dialog is gone: answers of running saves are dropped, the saves themselves go on. */
    fun detach() {
        detached = true
    }
}
