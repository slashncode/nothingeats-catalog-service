package io.github.slashncode.nothingeats.catalog.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random

private class DealWorld(
    at: String,
) {
    val restaurants = InMemoryRestaurants()
    val dishes = InMemoryDishes()
    val deals = InMemoryDeals()
    val events = RecordingCatalogEvents()
    val manage = ManageCatalog(restaurants, dishes, events, DirectTransactions())
    var clock: Clock = Clock.fixed(Instant.parse(at), ZoneOffset.UTC)
    val settings = DealSettings(concurrent = 2, durations = listOf(Duration.ofMinutes(45)), percentages = listOf(30))

    fun planner() = PlanDeals(restaurants, dishes, deals, events, DirectTransactions(), settings, clock, Random(7))

    suspend fun restaurant(
        slug: String,
        opensAt: String = "11:00",
        closesAt: String = "23:00",
    ) {
        manage.upsertRestaurant(restaurantInput(slug).copy(opensAt = opensAt, closesAt = closesAt))
        manage.upsertDish(slug, dishInput("big", priceCents = 1290))
        manage.upsertDish(slug, dishInput("side", position = 2, priceCents = 350))
    }
}

class DealsTest :
    FunSpec({
        test("keeps the configured number of deals running, one per restaurant, never on cheap sides") {
            // 12:00 in Berlin (UTC+1 in November).
            val world = DealWorld("2026-11-02T11:00:00Z")
            listOf("a", "b", "c").forEach { world.restaurant("restaurant-$it") }

            val first = world.planner()()

            first.started shouldHaveSize 2
            first.started.map { it.restaurantId }.toSet() shouldHaveSize 2
            first.started.forEach {
                it.regularPrice.cents shouldBe 1290
                it.price.cents shouldBe 900 // 30 % off, rounded to ten cents
                Duration.between(it.startsAt, it.endsAt) shouldBe Duration.ofMinutes(45)
            }
            world.events.events.filter { it.startsWith("deal:") } shouldHaveSize 2
            world.planner()().started.shouldBeEmpty()
        }

        test("a deal ends, the next one starts, ended deals are deleted after a day") {
            val world = DealWorld("2026-11-02T11:00:00Z")
            listOf("a", "b", "c").forEach { world.restaurant("restaurant-$it") }
            world.planner()()

            world.clock = Clock.offset(world.clock, Duration.ofMinutes(46))
            world.planner()().started shouldHaveSize 2

            world.clock = Clock.offset(world.clock, Duration.ofDays(2))
            world.planner()().deleted shouldBe 4
        }

        test("no deals at closed kitchens, nor ones that would outlive the opening hours") {
            // 22:30 in Berlin: one restaurant closes at 23:00 (too soon), one is open past midnight.
            val world = DealWorld("2026-11-02T21:30:00Z")
            world.restaurant("closes-soon")
            world.restaurant("night-owl", opensAt = "18:00", closesAt = "02:00")

            val planned = world.planner()()

            planned.started shouldHaveSize 1
            world.restaurants
                .findById(planned.started.single().restaurantId)!!
                .slug.value shouldBe "night-owl"
        }

        test("another pod holding the planning lock means this one plans nothing") {
            val world = DealWorld("2026-11-02T11:00:00Z")
            world.restaurant("restaurant-a")
            world.deals.lockAvailable = false

            world.planner()().started.shouldBeEmpty()
        }

        test("deals on offer come with their dish and restaurant") {
            val world = DealWorld("2026-11-02T11:00:00Z")
            world.restaurant("restaurant-a")
            world.planner()()

            val browse = BrowseDeals(world.deals, world.dishes, world.restaurants, world.clock)

            browse
                .active()
                .single()
                .restaurant.slug.value shouldBe "restaurant-a"
            browse.byDish().keys shouldBe
                setOf(
                    browse
                        .active()
                        .single()
                        .dish.id,
                )
        }
    })
