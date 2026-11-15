package org.schabi.newpipe.enhancements.groupcreateinline

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers

/** [GroupSaver] that writes on the io scheduler and answers on the main thread. */
class RxGroupSaver(private val table: GroupTable) : GroupSaver {

    private val disposables = CompositeDisposable()

    override fun createGroups(names: List<String>, subscriptionId: Long, callback: SaveCallback) {
        disposables.add(
            Completable
                .fromAction { GroupWriter.createGroupsWithMember(table, names, subscriptionId) }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { callback.onResult(null) },
                    { error -> callback.onResult(error) }
                )
        )
    }

    /** Drops the callbacks that were not delivered yet. */
    fun dispose() {
        disposables.clear()
    }
}
