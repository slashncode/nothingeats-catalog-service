package io.github.slashncode.nothingeats.catalog.adapters.persistence

import io.github.slashncode.nothingeats.catalog.application.DealSettings
import io.github.slashncode.nothingeats.catalog.application.ImageInput
import io.github.slashncode.nothingeats.catalog.application.ManageCatalog
import io.github.slashncode.nothingeats.catalog.application.RecordingCatalogEvents
import io.github.slashncode.nothingeats.catalog.application.Translated
import io.github.slashncode.nothingeats.catalog.application.dishInput
import io.github.slashncode.nothingeats.catalog.application.restaurantInput
import io.github.slashncode.nothingeats.catalog.domain.Slug
import io.github.slashncode.nothingeats.platform.persistence.FlywayMigrations
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import io.github.slashncode.nothingeats.platform.testing.PostgresTestDatabases
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.uuid.Uuid

class ExposedCatalogTest :
    FunSpec({
        lateinit var database: PostgresDatabase

        beforeTest {
            val config = PostgresTestDatabases.create()
            FlywayMigrations(config).migrate()
            database = PostgresDatabase(config).also { it.start() }
        }

        afterTest { database.stop() }

        fun manage() =
            ManageCatalog(
                ExposedRestaurants(database),
                ExposedDishes(database),
                ExposedPendingDishes(database),
                RecordingCatalogEvents(),
                ExposedTransactions(database),
            )

        test("restaurants round-trip and page by slug, optionally by cuisine; archived ones stay out of pages") {
            val restaurants = ExposedRestaurants(database)
            val manage = manage()
            val pizza = manage.publishRestaurant(Uuid.random(), restaurantInput(slug = "phantom-pizza", cuisine = "italian"))
            manage.publishRestaurant(
                Uuid.random(),
                restaurantInput(slug = "ghost-gyros", cuisine = "greek").copy(opensAt = "18:00", closesAt = "02:00"),
            )
            manage.publishRestaurant(Uuid.random(), restaurantInput(slug = "air-burger", cuisine = "american"))
            val gone = manage.publishRestaurant(Uuid.random(), restaurantInput(slug = "closed-down").copy(archived = true))

            restaurants.findBySlug(Slug("phantom-pizza")) shouldBe pizza
            restaurants.findById(pizza.id) shouldBe pizza
            restaurants.findById(gone.id) shouldBe gone
            restaurants.page(null, 2, null).map { it.slug.value } shouldContainExactly listOf("air-burger", "ghost-gyros")
            restaurants.page(Slug("ghost-gyros"), 2, null).map { it.slug.value } shouldContainExactly listOf("phantom-pizza")
            restaurants.all().map { it.slug.value } shouldContainExactly listOf("air-burger", "ghost-gyros", "phantom-pizza")
            restaurants
                .page(null, 10, Slug("greek"))
                .single()
                .closesAt
                .toString() shouldBe "02:00"
        }

        test("dishes round-trip with their tag arrays and images") {
            val dishes = ExposedDishes(database)
            val manage = manage()
            val restaurant = manage.publishRestaurant(Uuid.random(), restaurantInput())
            val photo =
                ImageInput(
                    "/media/2/m.webp",
                    "/media/2/m-640x400.webp",
                    "/media/2/m-1280x640.webp",
                    1200,
                    800,
                    50.0,
                    30.0,
                    Translated("Pizza", "Pizza"),
                )
            val dish = manage.publishDish(Uuid.random(), restaurant.id, dishInput().copy(image = photo))!!
            manage.publishDish(Uuid.random(), restaurant.id, dishInput(slug = "diavola", position = 2))

            dishes.findById(dish.id) shouldBe dish
            dishes.findById(dish.id)!!.image!!.focalY shouldBe 30.0
            dishes.findBySlug(restaurant.id, Slug("margherita")) shouldBe dish
            dishes.ofRestaurant(restaurant.id).map { it.slug.value }.sorted() shouldContainExactly listOf("diavola", "margherita")
        }

        test("a dish waits in the database until its restaurant arrives") {
            val dishes = ExposedDishes(database)
            val pending = ExposedPendingDishes(database)
            val manage = manage()
            val restaurantId = Uuid.random()
            val dishId = Uuid.random()

            manage.publishDish(dishId, restaurantId, dishInput()).shouldBeNull()
            dishes.findById(dishId).shouldBeNull()

            manage.publishRestaurant(restaurantId, restaurantInput())

            dishes.findById(dishId)!!.slug.value shouldBe "margherita"
            pending.take(restaurantId).shouldBeEmpty()
        }

        test("the planner's settings are one row that the latest publish replaces") {
            val settings = ExposedDealPlannerSettings(database)
            settings.current().shouldBeNull()

            settings.save(DealSettings(concurrent = 2))
            settings.save(
                DealSettings(
                    enabled = false,
                    concurrent = 4,
                    percentages = listOf(15),
                    minimumPriceCents = 800,
                ),
            )

            settings.current() shouldBe
                DealSettings(
                    enabled = false,
                    concurrent = 4,
                    percentages = listOf(15),
                    minimumPriceCents = 800,
                )
        }
    })
