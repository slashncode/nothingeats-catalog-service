package io.github.slashncode.nothingeats.catalog.application

import io.github.slashncode.nothingeats.catalog.domain.Dish
import io.github.slashncode.nothingeats.catalog.domain.InvalidCatalogData
import io.github.slashncode.nothingeats.catalog.domain.Restaurant
import io.github.slashncode.nothingeats.catalog.domain.Slug
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import io.github.slashncode.nothingeats.platform.core.error.Violation
import java.util.Base64
import kotlin.uuid.Uuid

data class Page<T>(
    val items: List<T>,
    /** Opaque; null on the last page. */
    val nextCursor: String?,
)

data class Menu(
    val restaurant: Restaurant,
    val dishes: List<Dish>,
)

data class DishWithRestaurant(
    val dish: Dish,
    val restaurant: Restaurant,
)

/** What customers see: restaurants page by page, a restaurant's menu, a single dish. */
class BrowseCatalog(
    private val restaurants: Restaurants,
    private val dishes: Dishes,
) {
    suspend fun restaurants(
        cursor: String?,
        limit: Int,
        cuisine: String?,
    ): Page<Restaurant> {
        val size = limit.coerceIn(1, MAX_PAGE)
        val after = cursor?.let { decodeCursor(it) }
        val cuisineSlug = cuisine?.let { slugOrInvalid(it, "cuisine") }
        val page = restaurants.page(after, size + 1, cuisineSlug)
        val items = page.take(size)
        return Page(items, if (page.size > size) encodeCursor(items.last().slug) else null)
    }

    suspend fun menu(restaurantSlug: String): Menu {
        val restaurant = restaurantOrNotFound(restaurantSlug)
        return Menu(restaurant, dishes.ofRestaurant(restaurant.id).sortedWith(compareBy({ it.position }, { it.slug.value })))
    }

    suspend fun dish(id: String): DishWithRestaurant {
        val dish = runCatching { Uuid.parse(id) }.getOrNull()?.let { dishes.findById(it) } ?: throw dishNotFound(id)
        val restaurant = restaurants.findById(dish.restaurantId) ?: throw dishNotFound(id)
        return DishWithRestaurant(dish, restaurant)
    }

    private suspend fun restaurantOrNotFound(slug: String): Restaurant =
        runCatching { Slug(slug) }.getOrNull()?.let { restaurants.findBySlug(it) }
            ?: throw AppError.NotFound(ErrorCode("RESTAURANT_NOT_FOUND"), "Restaurant $slug does not exist")

    private fun dishNotFound(id: String) = AppError.NotFound(ErrorCode("DISH_NOT_FOUND"), "Dish $id does not exist")

    private fun encodeCursor(slug: Slug) = Base64.getUrlEncoder().withoutPadding().encodeToString(slug.value.toByteArray())

    private fun decodeCursor(cursor: String): Slug =
        runCatching { Slug(String(Base64.getUrlDecoder().decode(cursor))) }.getOrNull()
            ?: throw AppError.Invalid(ErrorCode("CURSOR_INVALID"), "The cursor is invalid")

    private companion object {
        const val MAX_PAGE = 50
    }
}

internal fun slugOrInvalid(
    raw: String,
    field: String,
): Slug =
    try {
        Slug(raw)
    } catch (e: InvalidCatalogData) {
        throw catalogInvalid(field, e.message)
    }

internal fun catalogInvalid(
    field: String,
    reason: String?,
) = AppError.Invalid(
    ErrorCode("CATALOG_INVALID"),
    "The catalog data is invalid",
    listOf(Violation(field, reason ?: "invalid")),
)
