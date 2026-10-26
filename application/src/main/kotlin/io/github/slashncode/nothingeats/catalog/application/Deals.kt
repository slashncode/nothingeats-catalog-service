package io.github.slashncode.nothingeats.catalog.application

import io.github.slashncode.nothingeats.catalog.domain.Deal
import io.github.slashncode.nothingeats.catalog.domain.Dish
import io.github.slashncode.nothingeats.catalog.domain.Restaurant
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random
import kotlin.uuid.Uuid

data class DealSettings(
    /** How many flash deals run at the same time, each at a different restaurant. */
    val concurrent: Int = 3,
    val durations: List<Duration> = listOf(Duration.ofMinutes(30), Duration.ofMinutes(45), Duration.ofMinutes(60)),
    val percentages: List<Int> = listOf(20, 25, 30, 40),
    /** Cheap sides make dull deals. */
    val minimumPriceCents: Long = 500,
    /** Ended deals are kept this long, then deleted. */
    val retention: Duration = Duration.ofDays(1),
)

data class PlannedDeals(
    val started: List<Deal>,
    val deleted: Int,
)

/**
 * Keeps [DealSettings.concurrent] flash deals running: when deals have ended, new ones start at
 * restaurants that are open for the deal's whole duration (no deal on a closed kitchen, none that
 * outlives the opening hours). Runs on every pod; the planning lock lets one of them plan.
 */
class PlanDeals(
    private val restaurants: Restaurants,
    private val dishes: Dishes,
    private val deals: Deals,
    private val events: CatalogEvents,
    private val transactions: Transactions,
    private val settings: DealSettings = DealSettings(),
    private val clock: Clock = Clock.systemUTC(),
    private val random: Random = Random.Default,
) {
    suspend operator fun invoke(): PlannedDeals =
        transactions.run {
            if (!deals.tryLockPlanning()) return@run PlannedDeals(emptyList(), 0)
            val now = clock.instant()
            val deleted = deals.deleteEndedBefore(now - settings.retention)
            val active = deals.activeAt(now)
            val missing = settings.concurrent - active.size
            if (missing <= 0) return@run PlannedDeals(emptyList(), deleted)

            val busy = active.map { it.restaurantId }.toSet()
            val started = mutableListOf<Deal>()
            for (restaurant in restaurants.all().filter { it.id !in busy }.shuffled(random)) {
                if (started.size == missing) break
                plan(restaurant, now)?.let { started += it }
            }
            started.forEach {
                deals.insert(it)
                events.dealPublished(it)
            }
            PlannedDeals(started, deleted)
        }

    private suspend fun plan(
        restaurant: Restaurant,
        now: Instant,
    ): Deal? {
        val duration = settings.durations.random(random)
        val end = now + duration
        if (!restaurant.isOpenAt(now.atZone(BERLIN).toLocalTime()) ||
            !restaurant.isOpenAt(end.minusSeconds(1).atZone(BERLIN).toLocalTime())
        ) {
            return null
        }
        val dish: Dish =
            dishes
                .ofRestaurant(restaurant.id)
                .filter { it.available && it.price.cents >= settings.minimumPriceCents }
                .randomOrNull(random) ?: return null
        return Deal.of(dish, settings.percentages.random(random), now, duration)
    }

    private companion object {
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
    }
}

data class DealOffer(
    val deal: Deal,
    val dish: Dish,
    val restaurant: Restaurant,
)

/** What is on offer right now, for the deals strip and the menus. */
class BrowseDeals(
    private val deals: Deals,
    private val dishes: Dishes,
    private val restaurants: Restaurants,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun active(): List<DealOffer> =
        deals.activeAt(clock.instant()).mapNotNull { deal ->
            val dish = dishes.findById(deal.dishId)?.takeIf { it.available } ?: return@mapNotNull null
            val restaurant = restaurants.findById(deal.restaurantId) ?: return@mapNotNull null
            DealOffer(deal, dish, restaurant)
        }

    /** Running deals by dish id, for a menu or a dish page. */
    suspend fun byDish(): Map<Uuid, Deal> = deals.activeAt(clock.instant()).associateBy { it.dishId }
}
