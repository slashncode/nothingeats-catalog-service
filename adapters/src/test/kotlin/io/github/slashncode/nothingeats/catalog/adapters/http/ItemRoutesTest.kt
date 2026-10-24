package io.github.slashncode.nothingeats.catalog.adapters.http

import io.github.slashncode.nothingeats.catalog.application.CreateItem
import io.github.slashncode.nothingeats.catalog.application.GetItem
import io.github.slashncode.nothingeats.catalog.application.InMemoryItemRepository
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthChecks
import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness
import io.github.slashncode.nothingeats.platform.http.PlatformJson
import io.github.slashncode.nothingeats.platform.http.Problem
import io.github.slashncode.nothingeats.platform.http.installPlatform
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.post
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
import java.time.Clock

private fun itemsApi(block: suspend ApplicationTestBuilder.() -> Unit) =
    testApplication {
        val items = InMemoryItemRepository()
        val routes = ItemRoutes(CreateItem(items, Clock.systemUTC()), GetItem(items))
        application {
            installPlatform(Readiness(), HealthChecks(emptySet()))
            routing { with(routes) { register() } }
        }
        block()
    }

private suspend fun ApplicationTestBuilder.create(name: String): HttpResponse =
    client.post("/api/catalog/v1/items") {
        contentType(ContentType.Application.Json)
        setBody("""{"name":"$name"}""")
    }

private suspend fun HttpResponse.problem() = PlatformJson.decodeFromString(Problem.serializer(), bodyAsText())

class ItemRoutesTest :
    FunSpec({
        test("POST creates an item and GET returns it") {
            itemsApi {
                val created = create("Phantom Pizza")

                created.status shouldBe HttpStatusCode.Created
                val item = PlatformJson.decodeFromString(ItemResponse.serializer(), created.bodyAsText())
                created.headers[HttpHeaders.Location] shouldBe "/api/catalog/v1/items/${item.id}"

                val fetched = client.get("/api/catalog/v1/items/${item.id}")
                fetched.status shouldBe HttpStatusCode.OK
                PlatformJson.decodeFromString(ItemResponse.serializer(), fetched.bodyAsText()) shouldBe item
            }
        }

        test("validation and conflicts come back as problems") {
            itemsApi {
                create(" ").problem().code shouldBe "ITEM_INVALID"

                create("Phantom Pizza")
                val conflict = create("Phantom Pizza")
                conflict.status shouldBe HttpStatusCode.Conflict
                conflict.problem().code shouldBe "ITEM_NAME_TAKEN"
            }
        }

        test("malformed and unknown ids are not found") {
            itemsApi {
                client.get("/api/catalog/v1/items/not-a-uuid").problem().code shouldBe "ITEM_NOT_FOUND"
                client.get("/api/catalog/v1/items/0198f4a2-0000-7000-8000-000000000000").status shouldBe HttpStatusCode.NotFound
            }
        }
    })
