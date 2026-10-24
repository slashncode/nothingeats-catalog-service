package io.github.slashncode.nothingeats.catalog.app

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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.util.UUID

private fun freePort() = ServerSocket(0).use { it.localPort }

/** The whole service wired by the real composition root, against real Postgres and Kafka. */
class CatalogFlowTest :
    FunSpec({
        val http = HttpClient.newHttpClient()

        fun get(url: String): HttpResponse<String> = http.send(HttpRequest.newBuilder(URI(url)).build(), BodyHandlers.ofString())

        fun HttpResponse<String>.json() = Json.parseToJsonElement(body()).jsonObject

        /** Slugs of all restaurants published to Kafka, read like a projection would. */
        fun publishedRestaurants(expected: Int): Set<String> {
            val slugs = mutableSetOf<String>()
            KafkaClients.consumer(KafkaConfig(KafkaTestCluster.bootstrapServers), "test-${UUID.randomUUID()}").use { consumer ->
                consumer.subscribe(listOf(CatalogTopics.RESTAURANTS))
                val deadline = System.currentTimeMillis() + 20_000
                while (slugs.size < expected && System.currentTimeMillis() < deadline) {
                    for (record in consumer.poll(Duration.ofMillis(250))) {
                        val event = EventJson.decodeFromString(CloudEvent.serializer(), record.value().decodeToString())
                        event.type shouldBe CatalogTopics.RESTAURANT_PUBLISHED
                        slugs +=
                            event.data.jsonObject["slug"]!!
                                .jsonPrimitive.content
                    }
                }
            }
            return slugs
        }

        test("load the seed, browse it over HTTP and find it on Kafka") {
            KafkaTestCluster.createTopics(CatalogTopics.RESTAURANTS, CatalogTopics.DISHES)
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
                service.cronTasks.single { it.name == "load-seed" }.run()

                get("$api/restaurants?limit=50").json()["items"]!!.jsonArray shouldHaveSize 8
                val menu = get("$api/restaurants/phantom-pizza?lang=en").json()
                menu["sections"]!!.jsonArray.sumOf { it.jsonObject["dishes"]!!.jsonArray.size } shouldBe 6

                val unauthorized =
                    http.send(
                        HttpRequest
                            .newBuilder(URI("$api/admin/restaurants/phantom-pizza"))
                            .PUT(BodyPublishers.ofString("{}"))
                            .header("Content-Type", "application/json")
                            .build(),
                        BodyHandlers.ofString(),
                    )
                unauthorized.statusCode() shouldBe 401

                publishedRestaurants(expected = 8) shouldHaveSize 8
            } finally {
                service.lifecycle.stop()
            }
        }
    })
