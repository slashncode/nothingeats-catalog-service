package io.github.slashncode.nothingeats.catalog.adapters.http

import io.github.slashncode.nothingeats.catalog.application.AdminDeals
import io.github.slashncode.nothingeats.catalog.application.DealOffer
import io.github.slashncode.nothingeats.catalog.domain.Language
import io.github.slashncode.nothingeats.platform.http.HttpRoutes
import io.github.slashncode.nothingeats.platform.http.auth.requireRole
import io.ktor.server.request.acceptLanguage
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.time.Clock

@Serializable
data class AdminDealDishResponse(
    val id: String,
    val slug: String,
    val name: String,
)

@Serializable
data class AdminDealRestaurantResponse(
    val slug: String,
    val name: String,
)

@Serializable
data class AdminDealResponse(
    val id: String,
    /** `PLANNER` (can be ended here) or `BACKOFFICE` (cancelled in the backoffice). */
    val source: String,
    val percentOff: Int,
    val priceCents: Long,
    val regularPriceCents: Long,
    val startsAt: String,
    val endsAt: String,
    val running: Boolean,
    val dish: AdminDealDishResponse,
    val restaurant: AdminDealRestaurantResponse,
)

@Serializable
data class AdminDealsResponse(
    val items: List<AdminDealResponse>,
)

/**
 * `/api/catalog/v1/admin/deals` (role `admin`, the backoffice): the deals running now or later,
 * and `DELETE /{id}` ends a running planner deal at once (409 `DEAL_FROM_BACKOFFICE` for one the
 * backoffice scheduled).
 */
class CatalogAdminRoutes(
    private val deals: AdminDeals,
    private val clock: Clock = Clock.systemUTC(),
) : HttpRoutes {
    override fun Route.register() {
        requireRole("admin") {
            route("/api/catalog/v1/admin/deals") {
                get {
                    val language = Language.parse(call.request.queryParameters["lang"] ?: call.request.acceptLanguage())
                    call.respond(AdminDealsResponse(deals.current().map { it.response(language) }))
                }
                delete("/{id}") {
                    val ended = deals.end(call.parameters["id"].orEmpty())
                    call.respond(mapOf("id" to ended.id.toString(), "endsAt" to ended.endsAt.toString()))
                }
            }
        }
    }

    private fun DealOffer.response(language: Language) =
        AdminDealResponse(
            id = deal.id.toString(),
            source = deal.source.name,
            percentOff = deal.percentOff,
            priceCents = deal.price.cents,
            regularPriceCents = deal.regularPrice.cents,
            startsAt = deal.startsAt.toString(),
            endsAt = deal.endsAt.toString(),
            running = deal.isActiveAt(clock.instant()),
            dish = AdminDealDishResponse(dish.id.toString(), dish.slug.value, dish.name.of(language)),
            restaurant = AdminDealRestaurantResponse(restaurant.slug.value, restaurant.name),
        )
}
