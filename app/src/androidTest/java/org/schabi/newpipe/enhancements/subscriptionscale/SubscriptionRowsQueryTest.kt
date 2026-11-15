package org.schabi.newpipe.enhancements.subscriptionscale

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import java.util.concurrent.TimeUnit

/**
 * AC-4, AC-5 (plan 5.3 and section 7): `SubscriptionDAO.getAllRows()` on a real, in-memory
 * [AppDatabase]. What the JVM tests cannot show: that Room maps the six aliased columns of
 * [SUBSCRIPTION_ROWS_QUERY] onto [SubscriptionRow], keeps the order of today's list, and emits
 * again after a write.
 *
 * Instrumented: needs a device or emulator. Not run by CI, only compiled; run it with the
 * plan's device script. It uses only what `androidTest` already depends on.
 */
@RunWith(AndroidJUnit4::class)
class SubscriptionRowsQueryTest {

    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    private fun subscribe(name: String?, count: Long? = null, avatar: String? = null, serviceId: Int = 0): Long {
        val entity = SubscriptionEntity()
        entity.serviceId = serviceId
        entity.url = "https://example.invalid/channel/$name"
        entity.name = name
        entity.avatarUrl = avatar
        entity.subscriberCount = count
        entity.description = "a long description that the list does not load ".repeat(20)
        return database.subscriptionDAO().insert(entity)
    }

    private fun rows(): List<SubscriptionRow> = database.subscriptionDAO().getAllRows().blockingFirst()

    @Test
    fun ac4_getAllRows_mapsTheSixColumns() {
        val uid = subscribe("Some Channel", count = 12345, avatar = "https://img.invalid/a.jpg", serviceId = 3)

        assertEquals(
            "AC-4: uid, serviceId, url, name, avatarUrl and subscriberCount arrive under the row's property names",
            listOf(SubscriptionRow(uid, 3, "https://example.invalid/channel/Some Channel", "Some Channel", "https://img.invalid/a.jpg", 12345)),
            rows()
        )
    }

    @Test
    fun ac4_getAllRows_nullColumnsStayNull() {
        subscribe("No picture")

        val row = rows().single()

        assertNull(row.avatarUrl)
        assertNull("a channel never fetched has no subscriber count", row.subscriberCount)
        assertEquals(-1L, row.toChannelInfoItem().subscriberCount)
    }

    @Test
    fun ac4_getAllRows_emptyTable_isAnEmptyList() {
        assertEquals(emptyList<SubscriptionRow>(), rows())
    }

    /** The tab keeps its order: the slim query sorts as `getAll()` does, by name without regard to case. */
    @Test
    fun ac5_getAllRows_hasTheOrderOfTodaysList() {
        for (name in listOf("banana", "Cherry", "apple", "Banana 2", "cherry 1")) subscribe(name)

        val today = database.subscriptionDAO().getAll().blockingFirst()

        assertEquals(listOf("apple", "banana", "Banana 2", "Cherry", "cherry 1"), rows().map { it.name })
        assertEquals("AC-5: the same channels in the same order as the full query", today.map { it.uid }, rows().map { it.uid })
        assertEquals(today.map { it.toChannelInfoItem().name }, rows().map { it.toChannelInfoItem().name })
    }

    /** AC-5: subscribing and unsubscribing reach an open list: the query's `Flowable` emits after a write. */
    @Test
    fun ac5_getAllRows_emitsAgainAfterAWrite() {
        subscribe("first")
        val list = database.subscriptionDAO().getAllRows().test()
        list.awaitCount(1)
        assertEquals(listOf("first"), list.values().last().map { it.name })

        val second = subscribe("second")
        list.awaitCount(2)
        assertEquals("AC-5: a new subscription is emitted", listOf("first", "second"), list.values().last().map { it.name })

        database.subscriptionDAO().delete(database.subscriptionDAO().getSubscription(second)!!)
        list.awaitCount(3)
        assertEquals("AC-5: an unsubscribed channel is emitted as gone", listOf("first"), list.values().last().map { it.name })
        list.cancel()
    }

    /** The owner's table: 1600 channels through the slim query and [SubscriptionRows.sameNamesAndPictures]. */
    @Test
    fun ac4_scale_1600Subscriptions_slimListEqualsTheFullListsNamesAndPictures() {
        database.runInTransaction {
            for (i in 1..1600) subscribe("Channel %04d".format(i), count = i.toLong(), avatar = "https://img.invalid/$i.jpg")
        }

        val slim = rows()
        val full = database.subscriptionDAO().getAll().blockingFirst()
            .map { SubscriptionRow(it.uid, it.serviceId, it.url, it.name, it.avatarUrl, it.subscriberCount) }

        assertEquals(1600, slim.size)
        assertEquals("AC-4: the slim query gives what the full query gives, minus the description", full, slim)

        // a refresh batch rewrites 20 subscriber counts: the list the tab compares shows the same names and pictures
        database.runInTransaction {
            for (entity in database.subscriptionDAO().getAll().blockingFirst().take(20)) {
                entity.subscriberCount = 99
                database.subscriptionDAO().update(entity)
            }
        }
        val after = database.subscriptionDAO().getAllRows().timeout(30, TimeUnit.SECONDS).blockingFirst()
        assertEquals(true, SubscriptionRows.sameNamesAndPictures(slim, after))
        assertEquals(20, after.count { it.subscriberCount == 99L })
    }
}
