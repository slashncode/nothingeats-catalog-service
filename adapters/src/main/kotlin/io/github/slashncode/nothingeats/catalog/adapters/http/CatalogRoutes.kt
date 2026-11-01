package io.github.slashncode.nothingeats.catalog.adapters.http

import io.github.slashncode.nothingeats.catalog.application.BrowseCatalog
import io.github.slashncode.nothingeats.catalog.application.BrowseDeals
import io.github.slashncode.nothingeats.catalog.domain.Deal
import io.github.slashncode.nothingeats.catalog.domain.Dish
import io.github.slashncode.nothingeats.catalog.domain.Image
import io.github.slashncode.nothingeats.catalog.domain.Language
import io.github.slashncode.nothingeats.catalog.domain.Restaurant
import io.github.slashncode.nothingeats.platform.http.HttpRoutes
import io.github.slashncode.nothingeats.platform.http.auth.public
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.acceptLanguage
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.LocalTime
import java.time.ZoneId

@Serializable
data class Location(
    val latitude: Double,
    val longitude: Double,
)

/** An uploaded picture: [cardUrl] for cards, [wideUrl] for headers; focal point in percent. */
@Serializable
data class ImageResponse(
    val url: String,
    val cardUrl: String,
    val wideUrl: String,
    val alt: String,
    val width: Int? = null,
    val height: Int? = null,
    val focalX: Double? = null,
    val focalY: Double? = null,
)

@Serializable
data class RestaurantResponse(
    val slug: String,
    val name: String,
    val description: String,
    val cuisine: String,
    val rating: Double,
    val deliveryMinutes: Int,
    val deliveryFeeCents: Long,
    val minimumOrderCents: Long,
    val openNow: Boolean,
    val opensAt: String,
    val closesAt: String,
    val location: Location,
    val image: ImageResponse? = null,
)

@Serializable
data class RestaurantPageResponse(
    val items: List<RestaurantResponse>,
    val nextCursor: String?,
)

/** A running flash deal: [priceCents] instead of the dish's regular price until [endsAt]. */
@Serializable
data class DealResponse(
    val priceCents: Long,
    val percentOff: Int,
    val endsAt: String,
)

@Serializable
data class DishResponse(
    val id: String,
    val slug: String,
    val name: String,
    val description: String,
    val priceCents: Long,
    val dietary: List<String>,
    val allergens: List<String>,
    val spiceLevel: Int,
    val calories: Int,
    val moods: List<String>,
    val available: Boolean,
    val deal: DealResponse? = null,
    /** Null: the shop draws its illustration. */
    val image: ImageResponse? = null,
)

@Serializable
data class MenuSectionResponse(
    val name: String,
    val dishes: List<DishResponse>,
)

@Serializable
data class MenuResponse(
    val restaurant: RestaurantResponse,
    val sections: List<MenuSectionResponse>,
)

@Serializable
data class DishDetailResponse(
    val dish: DishResponse,
    val restaurant: RestaurantResponse,
)

@Serializable
data class DealRestaurantResponse(
    val slug: String,
    val name: String,
    val cuisine: String,
)

@Serializable
data class DealOfferResponse(
    val dish: DishResponse,
    val restaurant: DealRestaurantResponse,
    val startsAt: String,
)

@Serializable
data class DealsResponse(
    val items: List<DealOfferResponse>,
)

/**
 * `/api/catalog/v1`: public browsing in the caller's language (`?lang=` wins over Accept-Language).
 * "Open now" is evaluated in Berlin time. Changes come from the backoffice as events, not through here.
 */
class CatalogRoutes(
    private val browse: BrowseCatalog,
    private val deals: BrowseDeals,
    private val clock: Clock = Clock.system(ZoneId.of("Europe/Berlin")),
) : HttpRoutes {
    override fun Route.register() {
        route("/api/catalog/v1") {
            public {
                get("/restaurants") {
                    val page =
                        browse.restaurants(
                            cursor = call.request.queryParameters["cursor"],
                            limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_PAGE,
                            cuisine = call.request.queryParameters["cuisine"],
                        )
                    val language = call.language()
                    call.respond(RestaurantPageResponse(page.items.map { it.toResponse(language) }, page.nextCursor))
                }
                get("/restaurants/{slug}") {
                    val menu = browse.menu(call.parameters["slug"].orEmpty())
                    val language = call.language()
                    val running = deals.byDish()
                    val sections =
                        menu.dishes
                            .groupBy { it.section.of(language) }
                            .map { (name, dishes) -> MenuSectionResponse(name, dishes.map { it.toResponse(language, running[it.id]) }) }
                    call.respond(MenuResponse(menu.restaurant.toResponse(language), sections))
                }
                get("/dishes/{id}") {
                    val found = browse.dish(call.parameters["id"].orEmpty())
                    val language = call.language()
                    val deal = deals.byDish()[found.dish.id]
                    call.respond(DishDetailResponse(found.dish.toResponse(language, deal), found.restaurant.toResponse(language)))
                }
                get("/deals") {
                    val language = call.language()
                    val offers =
                        deals.active().map { offer ->
                            DealOfferResponse(
                                dish = offer.dish.toResponse(language, offer.deal),
                                restaurant =
                                    DealRestaurantResponse(
                                        offer.restaurant.slug.value,
                                        offer.restaurant.name,
                                        offer.restaurant.cuisine.value,
                                    ),
                                startsAt = offer.deal.startsAt.toString(),
                            )
                        }
                    call.respond(DealsResponse(offers))
                }
            }
        }
    }

    private fun ApplicationCall.language(): Language {
        val language = Language.parse(request.queryParameters["lang"] ?: request.acceptLanguage())
        response.header(HttpHeaders.ContentLanguage, language.tag)
        response.header(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
        return language
    }

    private fun Restaurant.toResponse(language: Language) =
        RestaurantResponse(
            slug = slug.value,
            name = name,
            description = description.of(language),
            cuisine = cuisine.value,
            rating = rating,
            deliveryMinutes = deliveryMinutes,
            deliveryFeeCents = deliveryFee.cents,
            minimumOrderCents = minimumOrder.cents,
            openNow = isOpenAt(LocalTime.now(clock)),
            opensAt = opensAt.toString(),
            closesAt = closesAt.toString(),
            location = Location(location.latitude, location.longitude),
            image = image?.toResponse(language),
        )

    private fun Dish.toResponse(
        language: Language,
        deal: Deal? = null,
    ) = DishResponse(
        id = id.toString(),
        slug = slug.value,
        name = name.of(language),
        description = description.of(language),
        priceCents = price.cents,
        dietary = dietary.map { it.name.lowercase() }.sorted(),
        allergens = allergens.map { it.name.lowercase() }.sorted(),
        spiceLevel = spiceLevel,
        calories = calories,
        moods = moods.sorted(),
        available = available,
        deal = deal?.let { DealResponse(it.price.cents, it.percentOff, it.endsAt.toString()) },
        image = image?.toResponse(language),
    )

    private fun Image.toResponse(language: Language) = ImageResponse(url, cardUrl, wideUrl, alt.of(language), width, height, focalX, focalY)

    private companion object {
        const val DEFAULT_PAGE = 20
    }
}
