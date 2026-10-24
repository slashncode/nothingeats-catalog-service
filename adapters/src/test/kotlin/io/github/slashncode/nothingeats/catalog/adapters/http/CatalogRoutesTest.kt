package io.github.slashncode.nothingeats.catalog.adapters.http

import io.github.slashncode.nothingeats.catalog.application.BrowseCatalog
import io.github.slashncode.nothingeats.catalog.application.DirectTransactions
import io.github.slashncode.nothingeats.catalog.application.InMemoryDishes
import io.github.slashncode.nothingeats.catalog.application.InMemoryRestaurants
import io.github.slashncode.nothingeats.catalog.application.ManageCatalog
import io.github.slashncode.nothingeats.catalog.application.RecordingCatalogEvents
import io.github.slashncode.nothingeats.catalog.application.Translated
import io.github.slashncode.nothingeats.catalog.application.dishInput
import io.github.slashncode.nothingeats.catalog.application.restaurantInput
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthChecks
import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness
import io.github.slashncode.nothingeats.platform.http.PlatformJson
import io.github.slashncode.nothingeats.platform.http.Problem
import io.github.slashncode.nothingeats.platform.http.installPlatform
import io.github.slashncode.nothingeats.platform.testing.TestTokens
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.KSerializer
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

private val tokens = TestTokens()

private val berlin = ZoneId.of("Europe/Berlin")

/** 12:00 in Berlin. */
private val noon = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), berlin)

private fun catalogApi(
    clock: Clock = noon,
    block: suspend ApplicationTestBuilder.(ManageCatalog) -> Unit,
) = testApplication {
    val restaurants = InMemoryRestaurants()
    val dishes = InMemoryDishes()
    val manage = ManageCatalog(restaurants, dishes, RecordingCatalogEvents(), DirectTransactions())
    val routes = CatalogRoutes(BrowseCatalog(restaurants, dishes), manage, clock)
    application {
        installPlatform(Readiness(), HealthChecks(emptySet()), tokens.jwtAuth)
        routing { with(routes) { register() } }
    }
    block(manage)
}

private suspend fun <T> HttpResponse.body(serializer: KSerializer<T>): T = PlatformJson.decodeFromString(serializer, bodyAsText())

private suspend fun HttpResponse.problem() = body(Problem.serializer())

private val restaurantBody =
    """
    {"name":"Ghost Gyros","description":{"de":"Gyros aus dem Jenseits.","en":"Gyros from beyond."},
     "cuisine":"greek","rating":4.5,"deliveryMinutes":35,"deliveryFeeCents":149,"minimumOrderCents":1200,
     "latitude":53.55,"longitude":9.93,"opensAt":"11:30","closesAt":"01:00"}
    """.trimIndent()

class CatalogRoutesTest :
    FunSpec({
        test("restaurants come in the requested language, page by page") {
            catalogApi { manage ->
                listOf("air-burger", "ghost-gyros", "phantom-pizza").forEach { manage.upsertRestaurant(restaurantInput(slug = it)) }

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
            catalogApi { manage ->
                manage.upsertRestaurant(restaurantInput())
                manage.upsertDish("phantom-pizza", dishInput(slug = "diavola", position = 2))
                manage.upsertDish("phantom-pizza", dishInput(slug = "margherita", position = 1))
                manage.upsertDish(
                    "phantom-pizza",
                    dishInput(slug = "tiramisu", position = 3).copy(section = Translated("Nachspeisen", "Desserts")),
                )

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
            catalogApi(clock = Clock.fixed(Instant.parse("2026-10-24T22:30:00Z"), berlin)) { manage ->
                manage.upsertRestaurant(restaurantInput(slug = "phantom-pizza"))
                manage.upsertRestaurant(restaurantInput(slug = "air-burger").copy(opensAt = "18:00", closesAt = "02:00"))

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
            catalogApi { manage ->
                manage.upsertRestaurant(restaurantInput())
                val dish = manage.upsertDish("phantom-pizza", dishInput())

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

        test("admin upserts need the admin role and validate their input") {
            catalogApi {
                suspend fun put(
                    body: String,
                    token: String?,
                ) = client.put("/api/catalog/v1/admin/restaurants/ghost-gyros") {
                    token?.let { bearerAuth(it) }
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }

                put(restaurantBody, token = null).status shouldBe HttpStatusCode.Unauthorized
                put(restaurantBody, tokens.accessToken()).status shouldBe HttpStatusCode.Forbidden

                val admin = tokens.accessToken(roles = setOf("admin"))
                put(restaurantBody, admin).status shouldBe HttpStatusCode.OK
                client.get("/api/catalog/v1/restaurants/ghost-gyros").status shouldBe HttpStatusCode.OK

                val invalid = put(restaurantBody.replace("\"11:30\"", "\"noon\""), admin)
                invalid.status shouldBe HttpStatusCode.UnprocessableEntity
                invalid.problem().code shouldBe "CATALOG_INVALID"

                val dish =
                    client.put("/api/catalog/v1/admin/restaurants/ghost-gyros/dishes/gyros-plate") {
                        bearerAuth(admin)
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                            {"section":{"de":"Hauptgerichte","en":"Mains"},"name":{"de":"Gyros-Teller","en":"Gyros plate"},
                             "description":{"de":"Mit Tzatziki.","en":"With tzatziki."},"priceCents":1450,"calories":1180,
                             "allergens":["milk"]}
                            """.trimIndent(),
                        )
                    }
                dish.status shouldBe HttpStatusCode.OK
                dish.body(DishResponse.serializer()).allergens shouldContainExactly listOf("milk")
            }
        }
    })
