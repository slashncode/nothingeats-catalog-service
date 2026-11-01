package io.github.slashncode.nothingeats.catalog.app

import io.github.slashncode.nothingeats.catalog.adapters.messaging.BackofficeTopics
import io.github.slashncode.nothingeats.catalog.adapters.messaging.CatalogTopics
import io.github.slashncode.nothingeats.platform.http.HttpServerConfig
import io.github.slashncode.nothingeats.platform.kafka.CloudEvent
import io.github.slashncode.nothingeats.platform.kafka.EventJson
import io.github.slashncode.nothingeats.platform.kafka.KafkaClients
import io.github.slashncode.nothingeats.platform.kafka.KafkaConfig
import io.github.slashncode.nothingeats.platform.testing.KafkaTestCluster
import io.github.slashncode.nothingeats.platform.testing.PostgresTestDatabases
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.kafka.clients.producer.ProducerRecord
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.time.Instant
import java.util.UUID

private fun freePort() = ServerSocket(0).use { it.localPort }

/** A backoffice event as its outbox writes it (nothingeats-backoffice docs/events.md). */
private fun backofficeEvent(
    type: String,
    subject: String,
    data: String,
) = """{"specversion":"1.0","id":"${UUID.randomUUID()}","source":"/nothingeats/backoffice","type":"$type","subject":"$subject",
       "time":"${Instant.now()}","datacontenttype":"application/json","data":$data}"""

/** The whole service wired by the real composition root, against real Postgres and Kafka. */
class CatalogFlowTest :
    FunSpec({
        val http = HttpClient.newHttpClient()

        fun get(url: String): HttpResponse<String> = http.send(HttpRequest.newBuilder(URI(url)).build(), BodyHandlers.ofString())

        fun HttpResponse<String>.json() = Json.parseToJsonElement(body()).jsonObject

        fun eventually(check: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 30_000
            while (!check()) {
                check(System.currentTimeMillis() < deadline) { "Timed out waiting for the catalog" }
                Thread.sleep(250)
            }
        }

        /** Data of the events catalog published on [topic], read like a projection would. */
        fun published(
            topic: String,
            expected: Int,
        ): List<JsonObject> {
            val found = mutableListOf<JsonObject>()
            KafkaClients.consumer(KafkaConfig(KafkaTestCluster.bootstrapServers), "test-${UUID.randomUUID()}").use { consumer ->
                consumer.subscribe(listOf(topic))
                val deadline = System.currentTimeMillis() + 20_000
                while (found.size < expected && System.currentTimeMillis() < deadline) {
                    for (record in consumer.poll(Duration.ofMillis(250))) {
                        found += EventJson.decodeFromString(CloudEvent.serializer(), record.value().decodeToString()).data.jsonObject
                    }
                }
            }
            return found
        }

        test("the backoffice's content reaches the menu, the deals and catalog's own topics") {
            KafkaTestCluster.createTopics(
                BackofficeTopics.RESTAURANTS,
                BackofficeTopics.DISHES,
                BackofficeTopics.DEALS,
                BackofficeTopics.DEAL_PLANNER,
                CatalogTopics.RESTAURANTS,
                CatalogTopics.DISHES,
                CatalogTopics.DEALS,
                "catalog-service.dlq",
            )
            val restaurantId = UUID.randomUUID().toString()
            val dishId = UUID.randomUUID().toString()
            val dealId = UUID.randomUUID().toString()
            val now = Instant.now()
            KafkaClients.producer(KafkaConfig(KafkaTestCluster.bootstrapServers), "test-backoffice").use { producer ->
                fun send(
                    topic: String,
                    key: String,
                    event: String,
                ) = producer.send(ProducerRecord(topic, key, event.toByteArray())).get()

                // The dish overtakes its restaurant; the planner is off, so only the scheduled deal runs.
                send(
                    BackofficeTopics.DISHES,
                    dishId,
                    backofficeEvent(
                        BackofficeTopics.DISH_PUBLISHED,
                        dishId,
                        """{"dishId":"$dishId","restaurantId":"$restaurantId","slug":"margherita-of-nothing",
                           "section":{"de":"Pizza","en":"Pizza"},"name":{"de":"Margherita des Nichts","en":"Margherita of Nothing"},
                           "description":{"de":"Tomaten, die du dir vorstellst.","en":"Tomatoes you imagine."},"priceCents":1090,
                           "dietary":["vegetarian"],"allergens":["gluten","milk"],"spiceLevel":0,"calories":820,"moods":["cheesy"],
                           "position":0,"available":true,"image":null,"archived":false}""",
                    ),
                )
                send(
                    BackofficeTopics.RESTAURANTS,
                    restaurantId,
                    backofficeEvent(
                        BackofficeTopics.RESTAURANT_PUBLISHED,
                        restaurantId,
                        """{"restaurantId":"$restaurantId","slug":"phantom-pizza","name":"Phantom Pizza",
                           "description":{"de":"Pizza, die es nicht gibt.","en":"Pizza that does not exist."},"cuisine":"italian",
                           "rating":4.7,"deliveryMinutes":30,"deliveryFeeCents":199,"minimumOrderCents":1200,"latitude":53.55,
                           "longitude":9.97,"opensAt":"00:00","closesAt":"23:59",
                           "image":{"url":"/media/1/front.webp","cardUrl":"/media/1/front-640x400.webp","wideUrl":"/media/1/front-1280x640.webp",
                                    "width":1600,"height":1000,"focalX":50,"focalY":50,"alt":{"de":"Der Laden","en":"The shop"}},"archived":false}""",
                    ),
                )
                send(
                    BackofficeTopics.DEAL_PLANNER,
                    "deal-planner",
                    backofficeEvent(
                        BackofficeTopics.DEAL_PLANNER_PUBLISHED,
                        "deal-planner",
                        """{"enabled":false,"concurrent":3,"durationsMinutes":[30],"percentages":[20],"minimumPriceCents":500}""",
                    ),
                )
                send(
                    BackofficeTopics.DEALS,
                    dealId,
                    backofficeEvent(
                        BackofficeTopics.DEAL_PUBLISHED,
                        dealId,
                        """{"dealId":"$dealId","dishId":"$dishId","percentOff":40,"startsAt":"${now.minusSeconds(60)}",
                           "endsAt":"${now.plusSeconds(3600)}","cancelled":false}""",
                    ),
                )
            }

            val port = freePort()
            val service =
                ServiceComponent::class.create(
                    ServiceConfig(
                        http = HttpServerConfig(port = port),
                        database = PostgresTestDatabases.create(),
                        kafka = KafkaConfig(KafkaTestCluster.bootstrapServers),
                        lifecycle = LifecycleConfig(drainDelay = kotlin.time.Duration.ZERO),
                    ),
                )
            val api = "http://localhost:$port/api/catalog/v1"
            service.migrations.migrate()
            service.lifecycle.start()
            try {
                eventually { get("$api/deals").json()["items"]!!.jsonArray.isNotEmpty() }

                val menu = get("$api/restaurants/phantom-pizza?lang=en").json()
                menu["restaurant"]!!
                    .jsonObject["image"]!!
                    .jsonObject["alt"]!!
                    .jsonPrimitive.content shouldBe "The shop"
                val dish =
                    menu["sections"]!!
                        .jsonArray
                        .single()
                        .jsonObject["dishes"]!!
                        .jsonArray
                        .single()
                        .jsonObject
                dish["name"]!!.jsonPrimitive.content shouldBe "Margherita of Nothing"
                dish["deal"]!!.jsonObject["priceCents"]!!.jsonPrimitive.content shouldBe "650"
                get("$api/deals").json()["items"]!!.jsonArray shouldHaveSize 1

                val unknown =
                    http.send(
                        HttpRequest
                            .newBuilder(URI("$api/admin/restaurants/phantom-pizza"))
                            .PUT(BodyPublishers.ofString("{}"))
                            .header("Content-Type", "application/json")
                            .build(),
                        BodyHandlers.ofString(),
                    )
                unknown.statusCode() shouldBe 404

                published(CatalogTopics.RESTAURANTS, 1).single()["slug"]!!.jsonPrimitive.content shouldBe "phantom-pizza"
                published(CatalogTopics.DISHES, 1).single()["restaurantName"]!!.jsonPrimitive.content shouldBe "Phantom Pizza"
                published(CatalogTopics.DEALS, 1).single()["percentOff"]!!.jsonPrimitive.content shouldBe "40"
            } finally {
                service.lifecycle.stop()
            }
        }
    })
