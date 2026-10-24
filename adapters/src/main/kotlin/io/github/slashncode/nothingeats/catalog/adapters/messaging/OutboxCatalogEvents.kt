package io.github.slashncode.nothingeats.catalog.adapters.messaging

import io.github.slashncode.nothingeats.catalog.application.CatalogEvents
import io.github.slashncode.nothingeats.catalog.domain.Dish
import io.github.slashncode.nothingeats.catalog.domain.LocalizedText
import io.github.slashncode.nothingeats.catalog.domain.Restaurant
import io.github.slashncode.nothingeats.platform.kafka.Outbox
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import kotlinx.serialization.Serializable

/** Contract: docs/events.md. Snapshots in all languages, so projections never call back. */
object CatalogTopics {
    const val RESTAURANTS = "catalog.restaurant.v1"
    const val DISHES = "catalog.dish.v1"
    const val RESTAURANT_PUBLISHED = "nothingeats.catalog.restaurant.published.v1"
    const val DISH_PUBLISHED = "nothingeats.catalog.dish.published.v1"
}

@Serializable
data class TextV1(
    val de: String,
    val en: String,
)

@Serializable
data class RestaurantPublishedV1(
    val restaurantId: String,
    val slug: String,
    val name: String,
    val description: TextV1,
    val cuisine: String,
    val rating: Double,
    val deliveryMinutes: Int,
    val deliveryFeeCents: Long,
    val minimumOrderCents: Long,
    val latitude: Double,
    val longitude: Double,
    val opensAt: String,
    val closesAt: String,
)

@Serializable
data class DishPublishedV1(
    val dishId: String,
    val restaurantId: String,
    val restaurantSlug: String,
    val restaurantName: String,
    val cuisine: String,
    val slug: String,
    val section: TextV1,
    val name: TextV1,
    val description: TextV1,
    val priceCents: Long,
    val dietary: List<String>,
    val allergens: List<String>,
    val spiceLevel: Int,
    val calories: Int,
    val moods: List<String>,
    val available: Boolean,
)

private fun LocalizedText.v1() = TextV1(de, en)

class OutboxCatalogEvents(
    private val outbox: Outbox,
    private val db: PostgresDatabase,
) : CatalogEvents {
    override suspend fun restaurantPublished(restaurant: Restaurant) {
        db.transaction {
            outbox.enqueue(
                CatalogTopics.RESTAURANTS,
                restaurant.id.toString(),
                CatalogTopics.RESTAURANT_PUBLISHED,
                RestaurantPublishedV1(
                    restaurantId = restaurant.id.toString(),
                    slug = restaurant.slug.value,
                    name = restaurant.name,
                    description = restaurant.description.v1(),
                    cuisine = restaurant.cuisine.value,
                    rating = restaurant.rating,
                    deliveryMinutes = restaurant.deliveryMinutes,
                    deliveryFeeCents = restaurant.deliveryFee.cents,
                    minimumOrderCents = restaurant.minimumOrder.cents,
                    latitude = restaurant.location.latitude,
                    longitude = restaurant.location.longitude,
                    opensAt = restaurant.opensAt.toString(),
                    closesAt = restaurant.closesAt.toString(),
                ),
                RestaurantPublishedV1.serializer(),
            )
        }
    }

    override suspend fun dishPublished(
        dish: Dish,
        restaurant: Restaurant,
    ) {
        db.transaction {
            outbox.enqueue(
                CatalogTopics.DISHES,
                dish.id.toString(),
                CatalogTopics.DISH_PUBLISHED,
                DishPublishedV1(
                    dishId = dish.id.toString(),
                    restaurantId = restaurant.id.toString(),
                    restaurantSlug = restaurant.slug.value,
                    restaurantName = restaurant.name,
                    cuisine = restaurant.cuisine.value,
                    slug = dish.slug.value,
                    section = dish.section.v1(),
                    name = dish.name.v1(),
                    description = dish.description.v1(),
                    priceCents = dish.price.cents,
                    dietary = dish.dietary.map { it.name.lowercase() }.sorted(),
                    allergens = dish.allergens.map { it.name.lowercase() }.sorted(),
                    spiceLevel = dish.spiceLevel,
                    calories = dish.calories,
                    moods = dish.moods.sorted(),
                    available = dish.available,
                ),
                DishPublishedV1.serializer(),
            )
        }
    }
}
