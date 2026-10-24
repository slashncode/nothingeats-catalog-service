package io.github.slashncode.nothingeats.catalog.adapters.persistence

import io.github.slashncode.nothingeats.catalog.application.ManageCatalog
import io.github.slashncode.nothingeats.catalog.application.RecordingCatalogEvents
import io.github.slashncode.nothingeats.catalog.application.dishInput
import io.github.slashncode.nothingeats.catalog.application.restaurantInput
import io.github.slashncode.nothingeats.catalog.domain.Slug
import io.github.slashncode.nothingeats.platform.persistence.FlywayMigrations
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import io.github.slashncode.nothingeats.platform.testing.PostgresTestDatabases
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class ExposedCatalogTest :
    FunSpec({
        lateinit var database: PostgresDatabase

        beforeTest {
            val config = PostgresTestDatabases.create()
            FlywayMigrations(config).migrate()
            database = PostgresDatabase(config).also { it.start() }
        }

        afterTest { database.stop() }

        fun catalog() =
            Triple(
                ExposedRestaurants(database),
                ExposedDishes(database),
                ManageCatalog(
                    ExposedRestaurants(database),
                    ExposedDishes(database),
                    RecordingCatalogEvents(),
                    ExposedTransactions(database),
                ),
            )

        test("restaurants round-trip and page by slug, optionally by cuisine") {
            val (restaurants, _, manage) = catalog()
            val pizza = manage.upsertRestaurant(restaurantInput(slug = "phantom-pizza", cuisine = "italian"))
            manage.upsertRestaurant(restaurantInput(slug = "ghost-gyros", cuisine = "greek").copy(opensAt = "18:00", closesAt = "02:00"))
            manage.upsertRestaurant(restaurantInput(slug = "air-burger", cuisine = "american"))

            restaurants.findBySlug(Slug("phantom-pizza")) shouldBe pizza
            restaurants.findById(pizza.id) shouldBe pizza
            restaurants.page(null, 2, null).map { it.slug.value } shouldContainExactly listOf("air-burger", "ghost-gyros")
            restaurants.page(Slug("ghost-gyros"), 2, null).map { it.slug.value } shouldContainExactly listOf("phantom-pizza")
            restaurants
                .page(null, 10, Slug("greek"))
                .single()
                .closesAt
                .toString() shouldBe "02:00"
        }

        test("an update keeps the row and its id") {
            val (restaurants, _, manage) = catalog()
            val first = manage.upsertRestaurant(restaurantInput())

            val updated = manage.upsertRestaurant(restaurantInput().copy(rating = 3.9))

            updated.id shouldBe first.id
            restaurants.page(null, 10, null).single().rating shouldBe 3.9
        }

        test("dishes round-trip with their tag arrays") {
            val (_, dishes, manage) = catalog()
            val restaurant = manage.upsertRestaurant(restaurantInput())
            val dish = manage.upsertDish("phantom-pizza", dishInput())
            manage.upsertDish("phantom-pizza", dishInput(slug = "diavola", position = 2))

            dishes.findById(dish.id) shouldBe dish
            dishes.findBySlug(restaurant.id, Slug("margherita")) shouldBe dish
            dishes.ofRestaurant(restaurant.id).map { it.slug.value }.sorted() shouldContainExactly listOf("diavola", "margherita")
        }
    })
