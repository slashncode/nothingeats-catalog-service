package io.github.slashncode.nothingeats.catalog.application

import io.github.slashncode.nothingeats.catalog.domain.Dish
import io.github.slashncode.nothingeats.catalog.domain.Restaurant
import io.github.slashncode.nothingeats.catalog.domain.Slug
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid

class InMemoryRestaurants : Restaurants {
    val all = ConcurrentHashMap<Uuid, Restaurant>()

    override suspend fun upsert(restaurant: Restaurant) {
        all[restaurant.id] = restaurant
    }

    override suspend fun findBySlug(slug: Slug) = all.values.firstOrNull { it.slug == slug }

    override suspend fun findById(id: Uuid) = all[id]

    override suspend fun page(
        after: Slug?,
        limit: Int,
        cuisine: Slug?,
    ) = all.values
        .filter { cuisine == null || it.cuisine == cuisine }
        .sortedBy { it.slug.value }
        .filter { after == null || it.slug.value > after.value }
        .take(limit)
}

class InMemoryDishes : Dishes {
    val all = ConcurrentHashMap<Uuid, Dish>()

    override suspend fun upsert(dish: Dish) {
        all[dish.id] = dish
    }

    override suspend fun findById(id: Uuid) = all[id]

    override suspend fun findBySlug(
        restaurantId: Uuid,
        slug: Slug,
    ) = all.values.firstOrNull { it.restaurantId == restaurantId && it.slug == slug }

    override suspend fun ofRestaurant(restaurantId: Uuid) = all.values.filter { it.restaurantId == restaurantId }
}

class RecordingCatalogEvents : CatalogEvents {
    val events = CopyOnWriteArrayList<String>()

    override suspend fun restaurantPublished(restaurant: Restaurant) {
        events += "restaurant:${restaurant.slug}"
    }

    override suspend fun dishPublished(
        dish: Dish,
        restaurant: Restaurant,
    ) {
        events += "dish:${restaurant.slug}/${dish.slug}"
    }
}

class DirectTransactions : Transactions {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}

fun restaurantInput(
    slug: String = "phantom-pizza",
    cuisine: String = "pizza",
) = RestaurantInput(
    slug = slug,
    name = "Phantom Pizza",
    description = Translated("Pizza, die es nicht gibt.", "Pizza that does not exist."),
    cuisine = cuisine,
    rating = 4.7,
    deliveryMinutes = 25,
    deliveryFeeCents = 199,
    minimumOrderCents = 1200,
    latitude = 53.55,
    longitude = 9.99,
    opensAt = "11:00",
    closesAt = "23:00",
)

fun dishInput(
    slug: String = "margherita",
    position: Int = 1,
    priceCents: Long = 990,
) = DishInput(
    slug = slug,
    section = Translated("Pizza", "Pizza"),
    name = Translated("Margherita", "Margherita"),
    description = Translated("Tomate, Mozzarella, Basilikum.", "Tomato, mozzarella, basil."),
    priceCents = priceCents,
    dietary = setOf("vegetarian"),
    allergens = setOf("gluten", "milk"),
    calories = 850,
    moods = setOf("Cheesy", "comfort"),
    position = position,
)
