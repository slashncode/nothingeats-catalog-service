package io.github.slashncode.nothingeats.catalog.adapters.messaging

import io.github.slashncode.nothingeats.catalog.application.DealSchedule
import io.github.slashncode.nothingeats.catalog.application.DealSettings
import io.github.slashncode.nothingeats.catalog.application.DishInput
import io.github.slashncode.nothingeats.catalog.application.ImageInput
import io.github.slashncode.nothingeats.catalog.application.ManageCatalog
import io.github.slashncode.nothingeats.catalog.application.RestaurantInput
import io.github.slashncode.nothingeats.catalog.application.ScheduleDeals
import io.github.slashncode.nothingeats.catalog.application.Translated
import io.github.slashncode.nothingeats.catalog.application.UpdateDealPlanner
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import io.github.slashncode.nothingeats.platform.kafka.CloudEvent
import io.github.slashncode.nothingeats.platform.kafka.EventHandler
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.uuid.Uuid

// Tolerant readers of the backoffice's contract (nothingeats-backoffice docs/events.md).

object BackofficeTopics {
    const val RESTAURANTS = "backoffice.restaurant.v1"
    const val DISHES = "backoffice.dish.v1"
    const val DEALS = "backoffice.deal.v1"
    const val DEAL_PLANNER = "backoffice.deal-planner.v1"
    const val RESTAURANT_PUBLISHED = "nothingeats.backoffice.restaurant.published.v1"
    const val DISH_PUBLISHED = "nothingeats.backoffice.dish.published.v1"
    const val DEAL_PUBLISHED = "nothingeats.backoffice.deal.published.v1"
    const val DEAL_PLANNER_PUBLISHED = "nothingeats.backoffice.deal-planner.published.v1"
}

@Serializable
internal data class BackofficeText(
    val de: String,
    val en: String,
)

@Serializable
internal data class BackofficeImage(
    val url: String,
    val cardUrl: String,
    val wideUrl: String,
    val width: Int? = null,
    val height: Int? = null,
    val focalX: Double? = null,
    val focalY: Double? = null,
    val alt: BackofficeText,
)

@Serializable
internal data class BackofficeRestaurant(
    val restaurantId: String,
    val slug: String,
    val name: String,
    val description: BackofficeText,
    val cuisine: String,
    val rating: Double,
    val deliveryMinutes: Int,
    val deliveryFeeCents: Long,
    val minimumOrderCents: Long,
    val latitude: Double,
    val longitude: Double,
    val opensAt: String,
    val closesAt: String,
    val image: BackofficeImage? = null,
    val archived: Boolean = false,
)

@Serializable
internal data class BackofficeDish(
    val dishId: String,
    val restaurantId: String,
    val slug: String,
    val section: BackofficeText,
    val name: BackofficeText,
    val description: BackofficeText,
    val priceCents: Long,
    val dietary: List<String> = emptyList(),
    val allergens: List<String> = emptyList(),
    val spiceLevel: Int = 0,
    val calories: Int,
    val moods: List<String> = emptyList(),
    val position: Int = 0,
    val available: Boolean = true,
    val image: BackofficeImage? = null,
    val archived: Boolean = false,
)

@Serializable
internal data class BackofficeDeal(
    val dealId: String,
    val dishId: String,
    val percentOff: Int,
    val startsAt: String,
    val endsAt: String,
    val cancelled: Boolean = false,
)

@Serializable
internal data class BackofficeDealPlanner(
    val enabled: Boolean,
    val concurrent: Int,
    /** Older snapshots carried durations; deals now run until midnight, so they are ignored. */
    val durationsMinutes: List<Int> = emptyList(),
    val percentages: List<Int>,
    val minimumPriceCents: Long,
)

private fun BackofficeText.input() = Translated(de, en)

private fun BackofficeImage.input() = ImageInput(url, cardUrl, wideUrl, width, height, focalX, focalY, alt.input())

private fun id(
    raw: String,
    field: String,
): Uuid = runCatching { Uuid.parse(raw) }.getOrNull() ?: throw AppError.Invalid(ErrorCode("CATALOG_INVALID"), "$field is not a UUID: $raw")

/**
 * The backoffice's restaurants and dishes into the catalog (record 040). Snapshots per document id;
 * the inbox makes a redelivery harmless and an unchanged snapshot publishes nothing.
 */
class BackofficeCatalogHandler(
    override val topic: String,
    private val manage: ManageCatalog,
) : EventHandler {
    private val log = LoggerFactory.getLogger(BackofficeCatalogHandler::class.java)

    override suspend fun handle(event: CloudEvent) {
        when (event.type) {
            BackofficeTopics.RESTAURANT_PUBLISHED -> {
                val r = event.decode(BackofficeRestaurant.serializer())
                manage.publishRestaurant(
                    id(r.restaurantId, "restaurantId"),
                    RestaurantInput(
                        slug = r.slug,
                        name = r.name,
                        description = r.description.input(),
                        cuisine = r.cuisine,
                        rating = r.rating,
                        deliveryMinutes = r.deliveryMinutes,
                        deliveryFeeCents = r.deliveryFeeCents,
                        minimumOrderCents = r.minimumOrderCents,
                        latitude = r.latitude,
                        longitude = r.longitude,
                        opensAt = r.opensAt,
                        closesAt = r.closesAt,
                        image = r.image?.input(),
                        archived = r.archived,
                    ),
                )
            }
            BackofficeTopics.DISH_PUBLISHED -> {
                val d = event.decode(BackofficeDish.serializer())
                val dish =
                    manage.publishDish(
                        id(d.dishId, "dishId"),
                        id(d.restaurantId, "restaurantId"),
                        DishInput(
                            slug = d.slug,
                            section = d.section.input(),
                            name = d.name.input(),
                            description = d.description.input(),
                            priceCents = d.priceCents,
                            dietary = d.dietary.toSet(),
                            allergens = d.allergens.toSet(),
                            spiceLevel = d.spiceLevel,
                            calories = d.calories,
                            moods = d.moods.toSet(),
                            position = d.position,
                            available = d.available,
                            image = d.image?.input(),
                            archived = d.archived,
                        ),
                    )
                if (dish == null) log.info("Dish {} waits for restaurant {}", d.dishId, d.restaurantId)
            }
            else -> log.debug("Ignoring {} on {}", event.type, topic)
        }
    }
}

/** Scheduled and cancelled flash deals from the backoffice. */
class BackofficeDealsHandler(
    private val schedule: ScheduleDeals,
) : EventHandler {
    override val topic = BackofficeTopics.DEALS

    override suspend fun handle(event: CloudEvent) {
        if (event.type != BackofficeTopics.DEAL_PUBLISHED) return
        val d = event.decode(BackofficeDeal.serializer())
        schedule(
            DealSchedule(
                dealId = id(d.dealId, "dealId"),
                dishId = id(d.dishId, "dishId"),
                percentOff = d.percentOff,
                startsAt = Instant.parse(d.startsAt),
                endsAt = Instant.parse(d.endsAt),
                cancelled = d.cancelled,
            ),
        )
    }
}

/** The deal planner's settings from the backoffice. */
class BackofficeDealPlannerHandler(
    private val update: UpdateDealPlanner,
) : EventHandler {
    override val topic = BackofficeTopics.DEAL_PLANNER

    override suspend fun handle(event: CloudEvent) {
        if (event.type != BackofficeTopics.DEAL_PLANNER_PUBLISHED) return
        val s = event.decode(BackofficeDealPlanner.serializer())
        update(
            DealSettings(
                enabled = s.enabled,
                concurrent = s.concurrent,
                percentages = s.percentages,
                minimumPriceCents = s.minimumPriceCents,
            ),
        )
    }
}
