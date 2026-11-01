package io.github.slashncode.nothingeats.catalog.application

import io.github.slashncode.nothingeats.catalog.domain.Allergen
import io.github.slashncode.nothingeats.catalog.domain.DietaryTag
import io.github.slashncode.nothingeats.catalog.domain.Dish
import io.github.slashncode.nothingeats.catalog.domain.GeoPoint
import io.github.slashncode.nothingeats.catalog.domain.Image
import io.github.slashncode.nothingeats.catalog.domain.InvalidCatalogData
import io.github.slashncode.nothingeats.catalog.domain.LocalizedText
import io.github.slashncode.nothingeats.catalog.domain.Money
import io.github.slashncode.nothingeats.catalog.domain.Restaurant
import io.github.slashncode.nothingeats.catalog.domain.Slug
import java.time.LocalTime
import kotlin.uuid.Uuid

data class Translated(
    val de: String,
    val en: String,
)

data class ImageInput(
    val url: String,
    val cardUrl: String,
    val wideUrl: String,
    val width: Int? = null,
    val height: Int? = null,
    val focalX: Double? = null,
    val focalY: Double? = null,
    val alt: Translated,
)

data class RestaurantInput(
    val slug: String,
    val name: String,
    val description: Translated,
    val cuisine: String,
    val rating: Double,
    val deliveryMinutes: Int,
    val deliveryFeeCents: Long,
    val minimumOrderCents: Long,
    val latitude: Double,
    val longitude: Double,
    /** `HH:mm` */
    val opensAt: String,
    val closesAt: String,
    val image: ImageInput? = null,
    val archived: Boolean = false,
)

data class DishInput(
    val slug: String,
    val section: Translated,
    val name: Translated,
    val description: Translated,
    val priceCents: Long,
    val dietary: Set<String> = emptySet(),
    val allergens: Set<String> = emptySet(),
    val spiceLevel: Int = 0,
    val calories: Int,
    val moods: Set<String> = emptySet(),
    val position: Int = 0,
    val available: Boolean = true,
    val image: ImageInput? = null,
    val archived: Boolean = false,
)

/**
 * Takes restaurants and dishes as the backoffice published them (record 040), under the backoffice's
 * ids, and keeps publishing catalog's own events. An update that changes nothing publishes nothing,
 * so a replayed topic is quiet. Rules the backoffice could not check (a slug taken by another
 * restaurant) end as `CATALOG_INVALID`, and the consumer parks the event in its dead-letter topic.
 */
class ManageCatalog(
    private val restaurants: Restaurants,
    private val dishes: Dishes,
    private val pending: PendingDishes,
    private val events: CatalogEvents,
    private val transactions: Transactions,
) {
    suspend fun publishRestaurant(
        id: Uuid,
        input: RestaurantInput,
    ): Restaurant =
        transactions.run {
            val restaurant = input.toRestaurant(id)
            if (restaurants.findBySlug(restaurant.slug)?.takeIf { it.id != id } != null) {
                throw catalogInvalid("slug", "${restaurant.slug} belongs to another restaurant")
            }
            if (restaurant != restaurants.findById(id)) {
                restaurants.upsert(restaurant)
                events.restaurantPublished(restaurant)
            }
            pending.take(id).forEach { (dishId, dish) -> applyDish(dishId, restaurant, dish) }
            restaurant
        }

    /** The dish, or null while it waits for its restaurant. */
    suspend fun publishDish(
        id: Uuid,
        restaurantId: Uuid,
        input: DishInput,
    ): Dish? =
        transactions.run {
            val restaurant = restaurants.findById(restaurantId)
            if (restaurant == null) {
                pending.park(id, restaurantId, input)
                null
            } else {
                applyDish(id, restaurant, input)
            }
        }

    private suspend fun applyDish(
        id: Uuid,
        restaurant: Restaurant,
        input: DishInput,
    ): Dish {
        val dish = input.toDish(id, restaurant.id)
        if (dishes.findBySlug(restaurant.id, dish.slug)?.takeIf { it.id != id } != null) {
            throw catalogInvalid("slug", "${dish.slug} belongs to another dish of ${restaurant.slug}")
        }
        if (dish != dishes.findById(id)) {
            dishes.upsert(dish)
            events.dishPublished(dish, restaurant)
        }
        return dish
    }

    private fun RestaurantInput.toRestaurant(id: Uuid): Restaurant =
        domain {
            Restaurant(
                id = id,
                slug = Slug(slug),
                name = name.trim(),
                description = LocalizedText(description.de, description.en),
                cuisine = Slug(cuisine),
                rating = rating,
                deliveryMinutes = deliveryMinutes,
                deliveryFee = Money(deliveryFeeCents),
                minimumOrder = Money(minimumOrderCents),
                location = GeoPoint(latitude, longitude),
                opensAt = time(opensAt, "opensAt"),
                closesAt = time(closesAt, "closesAt"),
                image = image?.toImage(),
                archived = archived,
            )
        }

    private fun DishInput.toDish(
        id: Uuid,
        restaurantId: Uuid,
    ): Dish =
        domain {
            Dish(
                id = id,
                restaurantId = restaurantId,
                slug = Slug(slug),
                section = LocalizedText(section.de, section.en),
                name = LocalizedText(name.de, name.en),
                description = LocalizedText(description.de, description.en),
                price = Money(priceCents),
                dietary = dietary.map { enumOrInvalid<DietaryTag>(it, "dietary") }.toSet(),
                allergens = allergens.map { enumOrInvalid<Allergen>(it, "allergens") }.toSet(),
                spiceLevel = spiceLevel,
                calories = calories,
                moods = moods.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet(),
                position = position,
                available = available,
                image = image?.toImage(),
                archived = archived,
            )
        }

    private fun ImageInput.toImage(): Image = Image(url, cardUrl, wideUrl, width, height, focalX, focalY, LocalizedText(alt.de, alt.en))

    private inline fun <T> domain(build: () -> T): T =
        try {
            build()
        } catch (e: InvalidCatalogData) {
            throw catalogInvalid(e.field, e.message)
        }

    private fun time(
        raw: String,
        field: String,
    ): LocalTime = runCatching { LocalTime.parse(raw) }.getOrNull() ?: throw catalogInvalid(field, "must be HH:mm")

    private inline fun <reified E : Enum<E>> enumOrInvalid(
        raw: String,
        field: String,
    ): E =
        enumValues<E>().firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: throw catalogInvalid(field, "unknown value $raw")
}
