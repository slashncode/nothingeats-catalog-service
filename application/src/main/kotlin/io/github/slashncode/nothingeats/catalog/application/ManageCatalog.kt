package io.github.slashncode.nothingeats.catalog.application

import io.github.slashncode.nothingeats.catalog.domain.Allergen
import io.github.slashncode.nothingeats.catalog.domain.DietaryTag
import io.github.slashncode.nothingeats.catalog.domain.Dish
import io.github.slashncode.nothingeats.catalog.domain.GeoPoint
import io.github.slashncode.nothingeats.catalog.domain.InvalidCatalogData
import io.github.slashncode.nothingeats.catalog.domain.LocalizedText
import io.github.slashncode.nothingeats.catalog.domain.Money
import io.github.slashncode.nothingeats.catalog.domain.Restaurant
import io.github.slashncode.nothingeats.catalog.domain.Slug
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import java.time.LocalTime
import kotlin.uuid.Uuid

data class Translated(
    val de: String,
    val en: String,
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
)

/**
 * Creates or updates restaurants and dishes by slug, keeping their ids stable. An update that
 * changes nothing publishes no event, so re-running the seed import is quiet.
 */
class ManageCatalog(
    private val restaurants: Restaurants,
    private val dishes: Dishes,
    private val events: CatalogEvents,
    private val transactions: Transactions,
) {
    suspend fun upsertRestaurant(input: RestaurantInput): Restaurant =
        transactions.run {
            val existing = restaurants.findBySlug(slugOrInvalid(input.slug, "slug"))
            val restaurant = input.toRestaurant(existing?.id ?: Uuid.random())
            if (restaurant != existing) {
                restaurants.upsert(restaurant)
                events.restaurantPublished(restaurant)
            }
            restaurant
        }

    suspend fun upsertDish(
        restaurantSlug: String,
        input: DishInput,
    ): Dish =
        transactions.run {
            val restaurant =
                restaurants.findBySlug(slugOrInvalid(restaurantSlug, "restaurant"))
                    ?: throw AppError.NotFound(ErrorCode("RESTAURANT_NOT_FOUND"), "Restaurant $restaurantSlug does not exist")
            val existing = dishes.findBySlug(restaurant.id, slugOrInvalid(input.slug, "slug"))
            val dish = input.toDish(existing?.id ?: Uuid.random(), restaurant.id)
            if (dish != existing) {
                dishes.upsert(dish)
                events.dishPublished(dish, restaurant)
            }
            dish
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
            )
        }

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

data class SeedRestaurant(
    val restaurant: RestaurantInput,
    val dishes: List<DishInput>,
)

/** `cron load-seed`: imports the demo catalog; safe to run again and again. */
class LoadSeed(
    private val manage: ManageCatalog,
) {
    suspend operator fun invoke(seed: List<SeedRestaurant>): Int =
        seed.sumOf { entry ->
            manage.upsertRestaurant(entry.restaurant)
            entry.dishes.forEach { manage.upsertDish(entry.restaurant.slug, it) }
            entry.dishes.size
        }
}
