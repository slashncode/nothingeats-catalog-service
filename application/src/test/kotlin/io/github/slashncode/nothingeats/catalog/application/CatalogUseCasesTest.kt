package io.github.slashncode.nothingeats.catalog.application

import io.github.slashncode.nothingeats.catalog.domain.Allergen
import io.github.slashncode.nothingeats.catalog.domain.DietaryTag
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private class Catalog {
    val restaurants = InMemoryRestaurants()
    val dishes = InMemoryDishes()
    val events = RecordingCatalogEvents()
    val manage = ManageCatalog(restaurants, dishes, events, DirectTransactions())
    val browse = BrowseCatalog(restaurants, dishes)
}

class CatalogUseCasesTest :
    FunSpec({
        test("upserts keep ids stable and publish only real changes") {
            val catalog = Catalog()

            val first = catalog.manage.upsertRestaurant(restaurantInput())
            val again = catalog.manage.upsertRestaurant(restaurantInput())
            val changed = catalog.manage.upsertRestaurant(restaurantInput().copy(deliveryMinutes = 40))

            again.id shouldBe first.id
            changed.id shouldBe first.id
            catalog.events.events shouldContainExactly listOf("restaurant:phantom-pizza", "restaurant:phantom-pizza")
        }

        test("dishes are parsed into domain values and belong to their restaurant") {
            val catalog = Catalog()
            catalog.manage.upsertRestaurant(restaurantInput())

            val dish = catalog.manage.upsertDish("phantom-pizza", dishInput())

            dish.dietary shouldBe setOf(DietaryTag.VEGETARIAN)
            dish.allergens shouldBe setOf(Allergen.GLUTEN, Allergen.MILK)
            dish.moods shouldBe setOf("cheesy", "comfort")
            catalog.events.events.last() shouldBe "dish:phantom-pizza/margherita"
        }

        test("invalid input names the field, unknown restaurants are not found") {
            val catalog = Catalog()
            catalog.manage.upsertRestaurant(restaurantInput())

            shouldThrow<AppError.Invalid> {
                catalog.manage.upsertRestaurant(
                    restaurantInput(slug = "Not A Slug"),
                )
            }.violations.single().field shouldBe
                "slug"
            shouldThrow<AppError.Invalid> { catalog.manage.upsertDish("phantom-pizza", dishInput().copy(allergens = setOf("unicorn"))) }
                .violations
                .single()
                .field shouldBe "allergens"
            shouldThrow<AppError.Invalid> {
                catalog.manage.upsertDish("phantom-pizza", dishInput().copy(dietary = setOf("vegan"), allergens = setOf("milk")))
            }.violations.single().field shouldBe "dietary"
            shouldThrow<AppError.NotFound> { catalog.manage.upsertDish("ghost-kitchen", dishInput()) }
        }

        test("restaurants come page by page, optionally by cuisine") {
            val catalog = Catalog()
            listOf("a-pizza" to "pizza", "b-sushi" to "sushi", "c-pizza" to "pizza").forEach { (slug, cuisine) ->
                catalog.manage.upsertRestaurant(restaurantInput(slug, cuisine))
            }

            val first = catalog.browse.restaurants(cursor = null, limit = 2, cuisine = null)
            val second = catalog.browse.restaurants(cursor = first.nextCursor, limit = 2, cuisine = null)

            first.items.map { it.slug.value } shouldContainExactly listOf("a-pizza", "b-sushi")
            second.items.map { it.slug.value } shouldContainExactly listOf("c-pizza")
            second.nextCursor shouldBe null
            catalog.browse
                .restaurants(null, 10, "pizza")
                .items
                .map { it.slug.value } shouldContainExactly listOf("a-pizza", "c-pizza")
            shouldThrow<AppError.Invalid> { catalog.browse.restaurants("%%%", 10, null) }
        }

        test("a menu lists dishes in menu order; unknown things are not found") {
            val catalog = Catalog()
            catalog.manage.upsertRestaurant(restaurantInput())
            catalog.manage.upsertDish("phantom-pizza", dishInput("tiramisu", position = 9))
            val margherita = catalog.manage.upsertDish("phantom-pizza", dishInput("margherita", position = 1))

            catalog.browse
                .menu("phantom-pizza")
                .dishes
                .map { it.slug.value } shouldContainExactly listOf("margherita", "tiramisu")
            catalog.browse
                .dish(margherita.id.toString())
                .restaurant.slug.value shouldBe "phantom-pizza"
            shouldThrow<AppError.NotFound> { catalog.browse.menu("nowhere") }
            shouldThrow<AppError.NotFound> { catalog.browse.dish("not-a-uuid") }
        }
    })
