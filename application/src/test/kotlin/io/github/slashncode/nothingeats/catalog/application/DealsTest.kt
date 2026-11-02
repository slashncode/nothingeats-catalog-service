package io.github.slashncode.nothingeats.catalog.application

import io.github.slashncode.nothingeats.catalog.domain.DealSource
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.uuid.Uuid

private class DealWorld(
    at: String,
) {
    val restaurants = InMemoryRestaurants()
    val dishes = InMemoryDishes()
    val deals = InMemoryDeals()
    val events = RecordingCatalogEvents()
    val manage = ManageCatalog(restaurants, dishes, InMemoryPendingDishes(), events, DirectTransactions())
    var clock: Clock = Clock.fixed(Instant.parse(at), ZoneOffset.UTC)
    val settings =
        InMemoryDealPlannerSettings(DealSettings(concurrent = 2, percentages = listOf(30)))

    fun planner() = PlanDeals(restaurants, dishes, deals, events, DirectTransactions(), settings, DealSettings(), clock, Random(7))

    fun schedule() = ScheduleDeals(deals, dishes, events, DirectTransactions(), clock)

    fun admin() = AdminDeals(deals, dishes, restaurants, events, DirectTransactions(), clock)

    /** The restaurant's id; its dishes are "big" (12.90) and "side" (3.50). */
    suspend fun restaurant(
        slug: String,
        opensAt: String = "11:00",
        closesAt: String = "23:00",
    ): Uuid {
        val id = Uuid.random()
        manage.publishRestaurant(id, restaurantInput(slug).copy(opensAt = opensAt, closesAt = closesAt))
        manage.publishDish(Uuid.random(), id, dishInput("big", priceCents = 1290))
        manage.publishDish(Uuid.random(), id, dishInput("side", position = 2, priceCents = 350))
        return id
    }

    suspend fun big(restaurantId: Uuid) = dishes.ofRestaurant(restaurantId).single { it.slug.value == "big" }
}

class DealsTest :
    FunSpec({
        test("keeps the configured number of deals of the day running, one per restaurant, never on cheap sides") {
            // 12:00 in Berlin (UTC+1 in November).
            val world = DealWorld("2026-11-02T11:00:00Z")
            listOf("a", "b", "c").forEach { world.restaurant("restaurant-$it") }

            val first = world.planner()()

            first.started shouldHaveSize 2
            first.started.map { it.restaurantId }.toSet() shouldHaveSize 2
            first.started.forEach {
                it.regularPrice.cents shouldBe 1290
                it.price.cents shouldBe 900 // 30 % off, rounded to ten cents
                // Until midnight in Hamburg (UTC+1 in November).
                it.endsAt shouldBe Instant.parse("2026-11-02T23:00:00Z")
            }
            world.events.events.filter { it.startsWith("deal:") } shouldHaveSize 2
            world.planner()().started.shouldBeEmpty()
        }

        test("the day's deals end at midnight, the next day brings new ones, ended deals are deleted after a day") {
            val world = DealWorld("2026-11-02T11:00:00Z")
            listOf("a", "b", "c").forEach { world.restaurant("restaurant-$it") }
            world.planner()()

            world.clock = Clock.offset(world.clock, Duration.ofHours(11))
            world.planner()().started.shouldBeEmpty()

            world.clock = Clock.offset(world.clock, Duration.ofHours(13))
            world.planner()().started shouldHaveSize 2

            world.clock = Clock.offset(world.clock, Duration.ofDays(2))
            world.planner()().deleted shouldBe 4
        }

        test("no deals at closed kitchens; a deal may outlive the opening hours, it ends at midnight") {
            // 23:30 in Berlin: one restaurant closed at 23:00, one is open past midnight.
            val world = DealWorld("2026-11-02T22:30:00Z")
            world.restaurant("closed")
            world.restaurant("night-owl", opensAt = "18:00", closesAt = "02:00")

            val planned = world.planner()()

            planned.started shouldHaveSize 1
            world.restaurants
                .findById(planned.started.single().restaurantId)!!
                .slug.value shouldBe "night-owl"
            planned.started.single().endsAt shouldBe Instant.parse("2026-11-02T23:00:00Z")
        }

        test("an editor ends a running planner deal; the slot is filled, never with the same dish that day") {
            val world = DealWorld("2026-11-02T11:00:00Z")
            listOf("a", "b", "c").forEach { world.restaurant("restaurant-$it") }
            val first = world.planner()().started.first()

            val ended = world.admin().end(first.id.toString())

            ended.endsAt shouldBe world.clock.instant()
            world.events.events.last() shouldBe "deal:${first.dishId}"
            val replacement = world.planner()().started.single()
            replacement.dishId shouldNotBe first.dishId
            world.admin().current().map { it.deal.id } shouldNotContain first.id
        }

        test("deals scheduled in the backoffice are cancelled there, not here; unknown deals are not found") {
            val world = DealWorld("2026-11-02T11:00:00Z")
            val a = world.restaurant("restaurant-a")
            val now = world.clock.instant()
            val scheduled =
                world.schedule()(DealSchedule(Uuid.random(), world.big(a).id, 30, now, now + Duration.ofHours(1), cancelled = false))!!

            shouldThrow<AppError.Conflict> { world.admin().end(scheduled.id.toString()) }.code.value shouldBe "DEAL_FROM_BACKOFFICE"
            shouldThrow<AppError.NotFound> { world.admin().end(Uuid.random().toString()) }
            shouldThrow<AppError.NotFound> { world.admin().end("not-an-id") }
            world
                .admin()
                .current()
                .single()
                .deal.source shouldBe DealSource.BACKOFFICE
        }

        test("another pod holding the planning lock means this one plans nothing") {
            val world = DealWorld("2026-11-02T11:00:00Z")
            world.restaurant("restaurant-a")
            world.deals.lockAvailable = false

            world.planner()().started.shouldBeEmpty()
        }

        test("the backoffice's settings rule the planner: switched off it plans nothing, and its numbers apply") {
            val world = DealWorld("2026-11-02T11:00:00Z")
            listOf("a", "b", "c").forEach { world.restaurant("restaurant-$it") }

            world.settings.settings = DealSettings(enabled = false)
            world.planner()().started.shouldBeEmpty()

            world.settings.settings = DealSettings(concurrent = 3, percentages = listOf(20))
            val planned = world.planner()().started
            planned shouldHaveSize 3
            planned.forEach { it.percentOff shouldBe 20 }
        }

        test("a scheduled deal counts toward the planner's number and keeps its restaurant to itself") {
            val world = DealWorld("2026-11-02T11:00:00Z")
            val a = world.restaurant("restaurant-a")
            listOf("b", "c").forEach { world.restaurant("restaurant-$it") }
            val start = Instant.parse("2026-11-02T10:30:00Z")

            val scheduled =
                world.schedule()(
                    DealSchedule(Uuid.random(), world.big(a).id, 50, start, start + Duration.ofHours(2), cancelled = false),
                )!!

            scheduled.price.cents shouldBe 650 // 50 % of 12.90, rounded to ten cents
            val planned = world.planner()().started
            planned shouldHaveSize 1
            planned.single().restaurantId shouldNotBe a
        }

        test("a cancelled deal ends at once; one cancelled before its start never runs") {
            val world = DealWorld("2026-11-02T11:00:00Z")
            val a = world.restaurant("restaurant-a")
            val dish = world.big(a).id
            val running = Uuid.random()
            val later = Uuid.random()
            val now = Instant.parse("2026-11-02T11:00:00Z")
            world.schedule()(DealSchedule(running, dish, 30, now - Duration.ofMinutes(10), now + Duration.ofHours(1), cancelled = false))
            world.schedule()(DealSchedule(later, dish, 30, now + Duration.ofHours(3), now + Duration.ofHours(4), cancelled = false))

            world.schedule()(
                DealSchedule(running, dish, 30, now - Duration.ofMinutes(10), now + Duration.ofHours(1), cancelled = true),
            )!!.endsAt shouldBe
                now
            val never =
                world.schedule()(
                    DealSchedule(later, dish, 30, now + Duration.ofHours(3), now + Duration.ofHours(4), cancelled = true),
                )!!

            never.endsAt shouldBe never.startsAt
            world.deals.activeAt(now).shouldBeEmpty()
            world.schedule()(DealSchedule(Uuid.random(), dish, 30, now, now + Duration.ofHours(1), cancelled = true)) shouldBe null
            shouldThrow<AppError.NotFound> {
                world.schedule()(DealSchedule(Uuid.random(), Uuid.random(), 30, now, now + Duration.ofHours(1), cancelled = false))
            }
        }

        test("planner settings are checked before they replace the old ones") {
            val settings = InMemoryDealPlannerSettings()
            shouldThrow<AppError.Invalid> { UpdateDealPlanner(settings)(DealSettings(percentages = listOf(95))) }
            shouldThrow<AppError.Invalid> { UpdateDealPlanner(settings)(DealSettings(percentages = emptyList())) }
            settings.settings shouldBe null

            UpdateDealPlanner(settings)(DealSettings(concurrent = 5))
            settings.settings!!.concurrent shouldBe 5
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
