package io.github.slashncode.nothingeats.catalog.application

import io.github.slashncode.nothingeats.catalog.domain.Dish
import io.github.slashncode.nothingeats.catalog.domain.Restaurant
import io.github.slashncode.nothingeats.catalog.domain.Slug
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

/** Published language of the catalog context, stored in the outbox within the caller's transaction. */
interface CatalogEvents {
    suspend fun restaurantPublished(restaurant: Restaurant)

    suspend fun dishPublished(
        dish: Dish,
        restaurant: Restaurant,
    )
}

interface Transactions {
    suspend fun <T> run(block: suspend () -> T): T
}
