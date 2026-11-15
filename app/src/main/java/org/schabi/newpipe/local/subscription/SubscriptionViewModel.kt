package org.schabi.newpipe.local.subscription

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import com.xwray.groupie.Group
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.local.feed.FeedDatabaseManager
import org.schabi.newpipe.local.subscription.item.ChannelItem
import org.schabi.newpipe.local.subscription.item.FeedGroupCardItem
import org.schabi.newpipe.util.DEFAULT_THROTTLE_TIMEOUT
import java.util.concurrent.TimeUnit

class SubscriptionViewModel(application: Application) : AndroidViewModel(application) {
    private var feedDatabaseManager: FeedDatabaseManager = FeedDatabaseManager(application)
    private var subscriptionManager = SubscriptionManager(application)
    private val onScreenGate = org.schabi.newpipe.enhancements.subscriptionscale.OnScreenGate() // ENH:subscription-scale
    private val channelItems = org.schabi.newpipe.enhancements.subscriptionscale.ReusingMapper<org.schabi.newpipe.enhancements.subscriptionscale.SubscriptionRow, ChannelItem>({ it.uid }) { ChannelItem(it.toChannelInfoItem(), it.uid, ChannelItem.ItemVersion.MINI) } // ENH:subscription-scale

    private val mutableStateLiveData = MutableLiveData<SubscriptionState>()
    private val mutableFeedGroupsLiveData = MutableLiveData<List<Group>>()
    val stateLiveData: LiveData<SubscriptionState> = mutableStateLiveData
    val feedGroupsLiveData: LiveData<List<Group>> = mutableFeedGroupsLiveData

    private val searchQuery = MutableLiveData<String>()
    val filteredSubscriptionsLiveData = MediatorLiveData<List<Group>>()

    init {
        // Setup the filtering logic
        filteredSubscriptionsLiveData.addSource(stateLiveData) { state ->
            if (state is SubscriptionState.LoadedState) {
                applyFilter(state.subscriptions, searchQuery.value)
            }
        }

        filteredSubscriptionsLiveData.addSource(searchQuery) { query ->
            val currentState = stateLiveData.value
            if (currentState is SubscriptionState.LoadedState) {
                applyFilter(currentState.subscriptions, query)
            }
        }
    }

    fun updateSearchQuery(query: String?) {
        searchQuery.value = query
    }

    fun getCurrentSearchQuery(): String {
        return searchQuery.value ?: ""
    }
    fun setOnScreen(onScreen: Boolean) = onScreenGate.setOnScreen(onScreen) // ENH:subscription-scale

    private fun applyFilter(subscriptions: List<Group>, query: String?) {
        if (query.isNullOrBlank()) {
            filteredSubscriptionsLiveData.value = subscriptions
            return
        }

        val lowercaseQuery = query.lowercase()
        val filteredList = subscriptions.filter { group ->
            when (group) {
                is ChannelItem -> {
                    group.infoItem.name.lowercase().contains(lowercaseQuery)
                }
                else -> true // Keep other items
            }
        }

        filteredSubscriptionsLiveData.value = filteredList
    }


    private var feedGroupItemsDisposable = feedDatabaseManager.groups()
        .let { onScreenGate.gate(it) } // ENH:subscription-scale
        .throttleLatest(DEFAULT_THROTTLE_TIMEOUT, TimeUnit.MILLISECONDS)
        .map { it.map(::FeedGroupCardItem) }
        .subscribeOn(Schedulers.io())
        .subscribe(
            { mutableFeedGroupsLiveData.postValue(it) },
            { mutableStateLiveData.postValue(SubscriptionState.ErrorState(it)) }
        )

    private var stateItemsDisposable = org.schabi.newpipe.enhancements.subscriptionscale.SubscriptionRows.listing(subscriptionManager.subscriptionTable().getAllRows(), onScreenGate) // ENH:subscription-scale
        .throttleLatest(DEFAULT_THROTTLE_TIMEOUT, TimeUnit.MILLISECONDS)
        .map { rows -> channelItems.map(rows) } // ENH:subscription-scale
        .subscribeOn(Schedulers.io())
        .subscribe(
            { mutableStateLiveData.postValue(SubscriptionState.LoadedState(it)) },
            { mutableStateLiveData.postValue(SubscriptionState.ErrorState(it)) }
        )

    override fun onCleared() {
        super.onCleared()
        stateItemsDisposable.dispose()
        feedGroupItemsDisposable.dispose()
    }

    sealed class SubscriptionState {
        data class LoadedState(val subscriptions: List<Group>) : SubscriptionState()
        data class ErrorState(val error: Throwable? = null) : SubscriptionState()
    }
}
