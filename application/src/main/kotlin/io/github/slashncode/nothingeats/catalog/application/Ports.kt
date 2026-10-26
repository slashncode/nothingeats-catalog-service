package io.github.slashncode.nothingeats.catalog.application

import io.github.slashncode.nothingeats.catalog.domain.Deal
import io.github.slashncode.nothingeats.catalog.domain.Dish
import io.github.slashncode.nothingeats.catalog.domain.Restaurant
import io.github.slashncode.nothingeats.catalog.domain.Slug
import java.time.Instant
import kotlin.uuid.Uuid

interface Restaurants {
    suspend fun upsert(restaurant: Restaurant)

    suspend fun findBySlug(slug: Slug): Restaurant?

    suspend fun findById(id: Uuid): Restaurant?

    /** Ordered by slug, starting after [after]; optionally one cuisine only. */
    suspend fun page(
        after: Slug?,
        limit: Int,
        cuisine: Slug?,
    ): List<Restaurant>

    suspend fun all(): List<Restaurant>
}

interface Dishes {
    suspend fun upsert(dish: Dish)

    suspend fun findById(id: Uuid): Dish?

    suspend fun findBySlug(
        restaurantId: Uuid,
        slug: Slug,
    ): Dish?

    suspend fun ofRestaurant(restaurantId: Uuid): List<Dish>
}

interface Deals {
    suspend fun insert(deal: Deal)

    /** Deals running at [time], soonest end first. */
    suspend fun activeAt(time: Instant): List<Deal>

    /** Deals that ended before [time] go; their events stay on the topic, harmlessly in the past. */
    suspend fun deleteEndedBefore(time: Instant): Int

    /**
     * Takes the planner's lock for the current transaction; false when another pod holds it.
     * Call inside [Transactions.run].
     */
    suspend fun tryLockPlanning(): Boolean
}

/** Published language of the catalog context, stored in the outbox within the caller's transaction. */
interface CatalogEvents {
    suspend fun restaurantPublished(restaurant: Restaurant)

    suspend fun dishPublished(
        dish: Dish,
        restaurant: Restaurant,
    )

    suspend fun dealPublished(deal: Deal)
}

interface Transactions {
    suspend fun <T> run(block: suspend () -> T): T
}
