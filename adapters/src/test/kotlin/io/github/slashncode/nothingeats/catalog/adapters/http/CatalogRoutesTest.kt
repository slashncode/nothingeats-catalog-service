package io.github.slashncode.nothingeats.catalog.adapters.http

import io.github.slashncode.nothingeats.catalog.application.AdminDeals
import io.github.slashncode.nothingeats.catalog.application.BrowseCatalog
import io.github.slashncode.nothingeats.catalog.application.BrowseDeals
import io.github.slashncode.nothingeats.catalog.application.DirectTransactions
import io.github.slashncode.nothingeats.catalog.application.DishInput
import io.github.slashncode.nothingeats.catalog.application.ImageInput
import io.github.slashncode.nothingeats.catalog.application.InMemoryDeals
import io.github.slashncode.nothingeats.catalog.application.InMemoryDishes
import io.github.slashncode.nothingeats.catalog.application.InMemoryPendingDishes
import io.github.slashncode.nothingeats.catalog.application.InMemoryRestaurants
import io.github.slashncode.nothingeats.catalog.application.ManageCatalog
import io.github.slashncode.nothingeats.catalog.application.RecordingCatalogEvents
import io.github.slashncode.nothingeats.catalog.application.RestaurantInput
import io.github.slashncode.nothingeats.catalog.application.Translated
import io.github.slashncode.nothingeats.catalog.application.dishInput
import io.github.slashncode.nothingeats.catalog.application.restaurantInput
import io.github.slashncode.nothingeats.catalog.domain.Deal
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthChecks
import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness
import io.github.slashncode.nothingeats.platform.http.PlatformJson
import io.github.slashncode.nothingeats.platform.http.Problem
import io.github.slashncode.nothingeats.platform.http.installPlatform
import io.github.slashncode.nothingeats.platform.testing.TestTokens
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.KSerializer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.uuid.Uuid

private val tokens = TestTokens()

private val berlin = ZoneId.of("Europe/Berlin")

/** 12:00 in Berlin. */
private val noon = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), berlin)

/** Content as the backoffice would publish it; restaurants by slug for the tests' convenience. */
private class Content(
    private val manage: ManageCatalog,
) {
    private val ids = mutableMapOf<String, Uuid>()

    suspend fun restaurant(input: RestaurantInput = restaurantInput()) =
        manage.publishRestaurant(
            ids.getOrPut(input.slug) {
                Uuid.random()
            },
            input,
        )

    suspend fun dish(
        input: DishInput = dishInput(),
        restaurant: String = "phantom-pizza",
    ) = manage.publishDish(Uuid.random(), ids.getValue(restaurant), input)!!
}

private fun catalogApi(
    clock: Clock = noon,
    deals: InMemoryDeals = InMemoryDeals(),
    block: suspend ApplicationTestBuilder.(Content) -> Unit,
) = testApplication {
    val restaurants = InMemoryRestaurants()
    val dishes = InMemoryDishes()
    val manage = ManageCatalog(restaurants, dishes, InMemoryPendingDishes(), RecordingCatalogEvents(), DirectTransactions())
    val routes = CatalogRoutes(BrowseCatalog(restaurants, dishes), BrowseDeals(deals, dishes, restaurants, clock), clock)
    val admin = CatalogAdminRoutes(AdminDeals(deals, dishes, restaurants, RecordingCatalogEvents(), DirectTransactions(), clock), clock)
    application {
        installPlatform(Readiness(), HealthChecks(emptySet()), tokens.jwtAuth)
        routing {
            with(routes) { register() }
            with(admin) { register() }
        }
    }
    block(Content(manage))
}

private suspend fun <T> HttpResponse.body(serializer: KSerializer<T>): T = PlatformJson.decodeFromString(serializer, bodyAsText())

private suspend fun HttpResponse.problem() = body(Problem.serializer())

class CatalogRoutesTest :
    FunSpec({
        test("a running deal shows on the menu and in the deals list; an ended one nowhere") {
            val deals = InMemoryDeals()
            catalogApi(deals = deals) { content ->
                content.restaurant(restaurantInput())
                val dish = content.dish(dishInput(priceCents = 1290))
                val other = content.dish(dishInput("funghi", position = 2, priceCents = 1190))
                deals.upsert(Deal.of(dish, 30, noon.instant().minusSeconds(60), Duration.ofMinutes(45)))
                deals.upsert(Deal.of(other, 20, noon.instant().minusSeconds(7200), Duration.ofMinutes(30)))

                val menu = client.get("/api/catalog/v1/restaurants/phantom-pizza").body(MenuResponse.serializer())
                val shown = menu.sections.flatMap { it.dishes }.associateBy { it.slug }
                shown.getValue("margherita").deal shouldBe DealResponse(900, 30, "2026-10-24T10:44:00Z")
                shown.getValue("funghi").deal.shouldBeNull()

                val offers = client.get("/api/catalog/v1/deals").body(DealsResponse.serializer())
                offers.items.map { it.dish.slug to it.restaurant.slug } shouldContainExactly listOf("margherita" to "phantom-pizza")
            }
        }

        test("admins see the running and coming deals and end a planner deal; customers are refused") {
            val deals = InMemoryDeals()
            catalogApi(deals = deals) { content ->
                content.restaurant(restaurantInput())
                val dish = content.dish(dishInput(priceCents = 1290))
                val running = Deal.forTheDay(dish, 30, noon.instant().minusSeconds(60), berlin)
                deals.upsert(running)
                val admin = tokens.accessToken(userId = "boss", roles = setOf("admin"))

                client
                    .get(
                        "/api/catalog/v1/admin/deals",
                    ) { header(HttpHeaders.Authorization, "Bearer ${tokens.accessToken()}") }
                    .status shouldBe
                    HttpStatusCode.Forbidden
                val listed =
                    client
                        .get("/api/catalog/v1/admin/deals?lang=de") { header(HttpHeaders.Authorization, "Bearer $admin") }
                        .body(AdminDealsResponse.serializer())
                        .items
                        .single()
                listed.source shouldBe "PLANNER"
                listed.running shouldBe true
                listed.endsAt shouldBe "2026-10-24T22:00:00Z"
                listed.restaurant.slug shouldBe "phantom-pizza"

                client
                    .delete(
                        "/api/catalog/v1/admin/deals/${running.id}",
                    ) { header(HttpHeaders.Authorization, "Bearer $admin") }
                    .status shouldBe
                    HttpStatusCode.OK
                deals.activeAt(noon.instant()).shouldBeEmpty()
            }
        }

        test("restaurants come in the requested language, page by page") {
            catalogApi { content ->
                listOf("air-burger", "ghost-gyros", "phantom-pizza").forEach { content.restaurant(restaurantInput(slug = it)) }

                val first = client.get("/api/catalog/v1/restaurants?limit=2&lang=en")
                first.headers[HttpHeaders.ContentLanguage] shouldBe "en"
                val page = first.body(RestaurantPageResponse.serializer())
                page.items.map { it.slug } shouldContainExactly listOf("air-burger", "ghost-gyros")
                page.items.first().description shouldBe "Pizza that does not exist."

                val rest = client.get("/api/catalog/v1/restaurants?limit=2&cursor=${page.nextCursor.shouldNotBeNull()}")
                val last = rest.body(RestaurantPageResponse.serializer())
                last.items.map { it.slug } shouldContainExactly listOf("phantom-pizza")
                last.nextCursor.shouldBeNull()

                val german = client.get("/api/catalog/v1/restaurants") { header(HttpHeaders.AcceptLanguage, "de-DE,de;q=0.9") }
                german
                    .body(RestaurantPageResponse.serializer())
                    .items
                    .first()
                    .description shouldBe "Pizza, die es nicht gibt."
            }
        }

        test("the menu groups dishes by section, in position order") {
            catalogApi { content ->
                content.restaurant(restaurantInput())
                content.dish(dishInput(slug = "diavola", position = 2))
                content.dish(dishInput(slug = "margherita", position = 1))
                content.dish(dishInput(slug = "tiramisu", position = 3).copy(section = Translated("Nachspeisen", "Desserts")))

                val menu = client.get("/api/catalog/v1/restaurants/phantom-pizza?lang=en").body(MenuResponse.serializer())

                menu.sections.map { it.name } shouldContainExactly listOf("Pizza", "Desserts")
                menu.sections
                    .first()
                    .dishes
                    .map { it.slug } shouldContainExactly listOf("margherita", "diavola")
                menu.sections
                    .first()
                    .dishes
                    .first()
                    .allergens shouldContainExactly listOf("gluten", "milk")
                menu.sections
                    .first()
                    .dishes
                    .first()
                    .dietary shouldContainExactly listOf("vegetarian")
            }
        }

        test("open now follows Berlin time, also for hours past midnight") {
            catalogApi(clock = Clock.fixed(Instant.parse("2026-10-24T22:30:00Z"), berlin)) { content ->
                content.restaurant(restaurantInput(slug = "phantom-pizza"))
                content.restaurant(restaurantInput(slug = "air-burger").copy(opensAt = "18:00", closesAt = "02:00"))

                val open =
                    client
                        .get("/api/catalog/v1/restaurants")
                        .body(RestaurantPageResponse.serializer())
                        .items
                        .associate { it.slug to it.openNow }

                open shouldBe mapOf("air-burger" to true, "phantom-pizza" to false)
            }
        }

        test("a dish comes with its restaurant; unknown things are problems") {
            catalogApi { content ->
                content.restaurant(restaurantInput())
                val dish = content.dish()

                val found = client.get("/api/catalog/v1/dishes/${dish.id}").body(DishDetailResponse.serializer())
                found.restaurant.slug shouldBe "phantom-pizza"
                found.dish.name shouldBe "Margherita"

                val missing = client.get("/api/catalog/v1/restaurants/ghost-kitchen")
                missing.status shouldBe HttpStatusCode.NotFound
                missing.problem().code shouldBe "RESTAURANT_NOT_FOUND"
                client.get("/api/catalog/v1/dishes/not-a-uuid").problem().code shouldBe "DISH_NOT_FOUND"
                client.get("/api/catalog/v1/restaurants?cursor=!!!").problem().code shouldBe "CURSOR_INVALID"
            }
        }

        test("images come with the alt text in the requested language; without one the dish has none") {
            catalogApi { content ->
                val front =
                    ImageInput(
                        "/media/1/front.webp",
                        "/media/1/front-640x400.webp",
                        "/media/1/front-1280x640.webp",
                        1600,
                        1000,
                        50.0,
                        40.0,
                        Translated("Der Laden", "The shop"),
                    )
                content.restaurant(restaurantInput().copy(image = front))
                content.dish(dishInput().copy(image = front.copy(alt = Translated("Eine Pizza", "A pizza"))))
                content.dish(dishInput("plain", position = 2))

                val menu = client.get("/api/catalog/v1/restaurants/phantom-pizza?lang=en").body(MenuResponse.serializer())

                menu.restaurant.image shouldBe
                    ImageResponse(
                        "/media/1/front.webp",
                        "/media/1/front-640x400.webp",
                        "/media/1/front-1280x640.webp",
                        "The shop",
                        1600,
                        1000,
                        50.0,
                        40.0,
                    )
                val dishes =
                    menu.sections
                        .single()
                        .dishes
                        .associateBy { it.slug }
                dishes.getValue("margherita").image!!.alt shouldBe "A pizza"
                dishes.getValue("plain").image.shouldBeNull()
            }
        }

        test("the API only reads: changes come from the backoffice") {
            catalogApi { content ->
                content.restaurant()
                client.put("/api/catalog/v1/admin/restaurants/phantom-pizza").status shouldBe HttpStatusCode.NotFound
            }
        }
    })
