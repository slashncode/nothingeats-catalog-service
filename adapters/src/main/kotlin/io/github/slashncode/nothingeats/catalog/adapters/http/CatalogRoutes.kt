package io.github.slashncode.nothingeats.catalog.adapters.http

import io.github.slashncode.nothingeats.catalog.application.BrowseCatalog
import io.github.slashncode.nothingeats.catalog.application.BrowseDeals
import io.github.slashncode.nothingeats.catalog.application.DishInput
import io.github.slashncode.nothingeats.catalog.application.ManageCatalog
import io.github.slashncode.nothingeats.catalog.application.RestaurantInput
import io.github.slashncode.nothingeats.catalog.application.Translated
import io.github.slashncode.nothingeats.catalog.domain.Deal
import io.github.slashncode.nothingeats.catalog.domain.Dish
import io.github.slashncode.nothingeats.catalog.domain.Language
import io.github.slashncode.nothingeats.catalog.domain.Restaurant
import io.github.slashncode.nothingeats.platform.http.HttpRoutes
import io.github.slashncode.nothingeats.platform.http.auth.public
import io.github.slashncode.nothingeats.platform.http.auth.requireRole
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.acceptLanguage
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
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

@Serializable
data class TranslatedRequest(
    val de: String,
    val en: String,
)

@Serializable
data class RestaurantRequest(
    val name: String,
    val description: TranslatedRequest,
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
data class DishRequest(
    val section: TranslatedRequest,
    val name: TranslatedRequest,
    val description: TranslatedRequest,
    val priceCents: Long,
    val dietary: Set<String> = emptySet(),
    val allergens: Set<String> = emptySet(),
    val spiceLevel: Int = 0,
    val calories: Int,
    val moods: Set<String> = emptySet(),
    val position: Int = 0,
    val available: Boolean = true,
)

private fun TranslatedRequest.toInput() = Translated(de, en)

/**
 * `/api/catalog/v1`: public browsing in the caller's language (`?lang=` wins over Accept-Language),
 * admin upserts for role `admin`. "Open now" is evaluated in Berlin time.
 */
class CatalogRoutes(
    private val browse: BrowseCatalog,
    private val deals: BrowseDeals,
    private val manage: ManageCatalog,
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
            requireRole("admin") {
                put("/admin/restaurants/{slug}") {
                    val request = call.receive<RestaurantRequest>()
                    val restaurant =
                        manage.upsertRestaurant(
                            RestaurantInput(
                                slug = call.parameters["slug"].orEmpty(),
                                name = request.name,
                                description = request.description.toInput(),
                                cuisine = request.cuisine,
                                rating = request.rating,
                                deliveryMinutes = request.deliveryMinutes,
                                deliveryFeeCents = request.deliveryFeeCents,
                                minimumOrderCents = request.minimumOrderCents,
                                latitude = request.latitude,
                                longitude = request.longitude,
                                opensAt = request.opensAt,
                                closesAt = request.closesAt,
                            ),
                        )
                    call.respond(restaurant.toResponse(call.language()))
                }
                put("/admin/restaurants/{slug}/dishes/{dish}") {
                    val request = call.receive<DishRequest>()
                    val dish =
                        manage.upsertDish(
                            call.parameters["slug"].orEmpty(),
                            DishInput(
                                slug = call.parameters["dish"].orEmpty(),
                                section = request.section.toInput(),
                                name = request.name.toInput(),
                                description = request.description.toInput(),
                                priceCents = request.priceCents,
                                dietary = request.dietary,
                                allergens = request.allergens,
                                spiceLevel = request.spiceLevel,
                                calories = request.calories,
                                moods = request.moods,
                                position = request.position,
                                available = request.available,
                            ),
                        )
                    call.respond(dish.toResponse(call.language()))
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
    )

    private companion object {
        const val DEFAULT_PAGE = 20
    }
}
