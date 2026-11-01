package io.github.slashncode.nothingeats.catalog.application

import io.github.slashncode.nothingeats.catalog.domain.Allergen
import io.github.slashncode.nothingeats.catalog.domain.DietaryTag
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.uuid.Uuid

private class Catalog {
    val restaurants = InMemoryRestaurants()
    val dishes = InMemoryDishes()
    val pending = InMemoryPendingDishes()
    val events = RecordingCatalogEvents()
    val manage = ManageCatalog(restaurants, dishes, pending, events, DirectTransactions())
    val browse = BrowseCatalog(restaurants, dishes)
    val pizzeria = Uuid.random()

    suspend fun restaurant(
        input: RestaurantInput = restaurantInput(),
        id: Uuid = pizzeria,
    ) = manage.publishRestaurant(id, input)

    suspend fun dish(
        input: DishInput = dishInput(),
        id: Uuid = Uuid.random(),
        restaurantId: Uuid = pizzeria,
    ) = manage.publishDish(id, restaurantId, input)
}

class CatalogUseCasesTest :
    FunSpec({
        test("the backoffice's ids are kept, and only real changes are published") {
            val catalog = Catalog()

            val first = catalog.restaurant()
            catalog.restaurant()
            catalog.restaurant(restaurantInput().copy(deliveryMinutes = 40))

            first.id shouldBe catalog.pizzeria
            catalog.events.events shouldContainExactly listOf("restaurant:phantom-pizza", "restaurant:phantom-pizza")
        }

        test("dishes are parsed into domain values and belong to their restaurant") {
            val catalog = Catalog()
            catalog.restaurant()

            val dish = catalog.dish()!!

            dish.dietary shouldBe setOf(DietaryTag.VEGETARIAN)
            dish.allergens shouldBe setOf(Allergen.GLUTEN, Allergen.MILK)
            dish.moods shouldBe setOf("cheesy", "comfort")
            catalog.events.events.last() shouldBe "dish:phantom-pizza/margherita"
        }

        test("a dish that overtakes its restaurant waits and is published with it") {
            val catalog = Catalog()
            val margherita = Uuid.random()

            catalog.dish(id = margherita).shouldBeNull()
            catalog.events.events.shouldBeEmpty()

            catalog.restaurant()

            catalog.events.events shouldContainExactly listOf("restaurant:phantom-pizza", "dish:phantom-pizza/margherita")
            catalog.dishes.findById(margherita)!!.restaurantId shouldBe catalog.pizzeria
            catalog.pending.waiting.keys
                .shouldBeEmpty()
        }

        test("invalid content names the field; a slug taken by another restaurant or dish is refused") {
            val catalog = Catalog()
            catalog.restaurant()
            catalog.dish()

            shouldThrow<AppError.Invalid> { catalog.restaurant(restaurantInput(slug = "Not A Slug"), Uuid.random()) }
                .violations
                .single()
                .field shouldBe "slug"
            shouldThrow<AppError.Invalid> { catalog.restaurant(restaurantInput(), Uuid.random()) }
                .violations
                .single()
                .field shouldBe "slug"
            shouldThrow<AppError.Invalid> { catalog.dish(dishInput()) }.violations.single().field shouldBe "slug"
            shouldThrow<AppError.Invalid> { catalog.dish(dishInput("other").copy(allergens = setOf("unicorn"))) }
                .violations
                .single()
                .field shouldBe "allergens"
            shouldThrow<AppError.Invalid> {
                catalog.dish(dishInput("vegan-ish").copy(dietary = setOf("vegan"), allergens = setOf("milk")))
            }.violations.single().field shouldBe "dietary"
        }

        test("restaurants come page by page, optionally by cuisine, archived ones not at all") {
            val catalog = Catalog()
            listOf("a-pizza" to "pizza", "b-sushi" to "sushi", "c-pizza" to "pizza").forEach { (slug, cuisine) ->
                catalog.restaurant(restaurantInput(slug, cuisine), Uuid.random())
            }
            catalog.restaurant(restaurantInput("d-gone", "pizza").copy(archived = true), Uuid.random())

            val first = catalog.browse.restaurants(cursor = null, limit = 2, cuisine = null)
            val second = catalog.browse.restaurants(cursor = first.nextCursor, limit = 2, cuisine = null)

            first.items.map { it.slug.value } shouldContainExactly listOf("a-pizza", "b-sushi")
            second.items.map { it.slug.value } shouldContainExactly listOf("c-pizza")
            second.nextCursor shouldBe null
            catalog.browse
                .restaurants(null, 10, "pizza")
                .items
                .map { it.slug.value } shouldContainExactly listOf("a-pizza", "c-pizza")
            shouldThrow<AppError.NotFound> { catalog.browse.menu("d-gone") }
            shouldThrow<AppError.Invalid> { catalog.browse.restaurants("%%%", 10, null) }
        }

        test("a menu lists dishes in menu order without archived ones; unknown things are not found") {
            val catalog = Catalog()
            catalog.restaurant()
            catalog.dish(dishInput("tiramisu", position = 9))
            val margherita = catalog.dish(dishInput("margherita", position = 1))!!
            val retired = catalog.dish(dishInput("retired", position = 5).copy(archived = true))!!

            catalog.browse
                .menu("phantom-pizza")
                .dishes
                .map { it.slug.value } shouldContainExactly listOf("margherita", "tiramisu")
            catalog.browse
                .dish(margherita.id.toString())
                .restaurant.slug.value shouldBe "phantom-pizza"
            shouldThrow<AppError.NotFound> { catalog.browse.dish(retired.id.toString()) }
            shouldThrow<AppError.NotFound> { catalog.browse.menu("nowhere") }
            shouldThrow<AppError.NotFound> { catalog.browse.dish("not-a-uuid") }
        }

        test("images travel with restaurants and dishes") {
            val catalog = Catalog()
            val image =
                ImageInput(
                    "/media/1/front.webp",
                    "/media/1/front-640x400.webp",
                    "/media/1/front-1280x640.webp",
                    alt = Translated("Laden", "Shop"),
                )

            catalog
                .restaurant(restaurantInput().copy(image = image))
                .image!!
                .alt.en shouldBe "Shop"
            shouldThrow<AppError.Invalid> {
                catalog.dish(dishInput().copy(image = image.copy(url = "javascript:alert(1)")))
            }.violations.single().field shouldBe "image"
        }
    })
