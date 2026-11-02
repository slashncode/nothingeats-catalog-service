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

    /** Restaurants in the shop (not archived), ordered by slug, starting after [after]; optionally one cuisine only. */
    suspend fun page(
        after: Slug?,
        limit: Int,
        cuisine: Slug?,
    ): List<Restaurant>

    /** Every restaurant in the shop (not archived). */
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
    /** Inserts or replaces by id: planned deals are written once, scheduled ones may change. */
    suspend fun upsert(deal: Deal)

    suspend fun findById(id: Uuid): Deal?

    /** Deals running at [time], soonest end first. */
    suspend fun activeAt(time: Instant): List<Deal>

    /** Deals running at [time] or starting later, earliest start first (the backoffice's list). */
    suspend fun endingAfter(time: Instant): List<Deal>

    /** Dishes with a deal that ran or runs after [time]: a dish gets one deal a day. */
    suspend fun dishesWithDealsAfter(time: Instant): Set<Uuid>

    /** Deals that ended before [time] go; their events stay on the topic, harmlessly in the past. */
    suspend fun deleteEndedBefore(time: Instant): Int

    /**
     * Takes the planner's lock for the current transaction; false when another pod holds it.
     * Call inside [Transactions.run].
     */
    suspend fun tryLockPlanning(): Boolean
}

/**
 * Dishes whose restaurant has not arrived yet: the backoffice's dish and restaurant topics are read
 * side by side, so a dish can overtake its restaurant. They wait here and are applied with it.
 */
interface PendingDishes {
    suspend fun park(
        dishId: Uuid,
        restaurantId: Uuid,
        input: DishInput,
    )

    /** Removes and returns the dishes waiting for [restaurantId]. */
    suspend fun take(restaurantId: Uuid): List<Pair<Uuid, DishInput>>
}

/** The deal planner's settings as the backoffice published them last; null until it has. */
interface DealPlannerSettings {
    suspend fun current(): DealSettings?

    suspend fun save(settings: DealSettings)
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
