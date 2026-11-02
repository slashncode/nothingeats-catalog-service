package io.github.slashncode.nothingeats.catalog.application

import io.github.slashncode.nothingeats.catalog.domain.Deal
import io.github.slashncode.nothingeats.catalog.domain.DealSource
import io.github.slashncode.nothingeats.catalog.domain.Dish
import io.github.slashncode.nothingeats.catalog.domain.InvalidCatalogData
import io.github.slashncode.nothingeats.catalog.domain.Restaurant
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random
import kotlin.uuid.Uuid

/** The planner's settings; editors change them in the backoffice (`backoffice.deal-planner.v1`). */
data class DealSettings(
    val enabled: Boolean = true,
    /** How many deals run at the same time (scheduled ones included), each at a different restaurant. */
    val concurrent: Int = 3,
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
 * Keeps [DealSettings.concurrent] deals of the day running: each runs until midnight in Hamburg,
 * and a missing one starts at a restaurant that is open now (no deal on a closed kitchen; after
 * midnight the day's deals start as the kitchens open). A dish gets one deal a day, so a deal an
 * editor ended does not come straight back. Deals scheduled in the backoffice count and keep their
 * restaurant to themselves. Runs on every pod; the planning lock lets one of them plan.
 */
class PlanDeals(
    private val restaurants: Restaurants,
    private val dishes: Dishes,
    private val deals: Deals,
    private val events: CatalogEvents,
    private val transactions: Transactions,
    private val plannerSettings: DealPlannerSettings,
    /** Until the backoffice has published settings. */
    private val defaults: DealSettings = DealSettings(),
    private val clock: Clock = Clock.systemUTC(),
    private val random: Random = Random.Default,
) {
    suspend operator fun invoke(): PlannedDeals =
        transactions.run {
            if (!deals.tryLockPlanning()) return@run PlannedDeals(emptyList(), 0)
            val settings = plannerSettings.current() ?: defaults
            val now = clock.instant()
            val deleted = deals.deleteEndedBefore(now - settings.retention)
            if (!settings.enabled) return@run PlannedDeals(emptyList(), deleted)
            val active = deals.activeAt(now)
            val missing = settings.concurrent - active.size
            if (missing <= 0) return@run PlannedDeals(emptyList(), deleted)

            val busy = active.map { it.restaurantId }.toSet()
            val dealtToday = deals.dishesWithDealsAfter(startOfDay(now))
            val started = mutableListOf<Deal>()
            for (restaurant in restaurants.all().filter { it.id !in busy }.shuffled(random)) {
                if (started.size == missing) break
                plan(restaurant, now, settings, dealtToday)?.let { started += it }
            }
            started.forEach {
                deals.upsert(it)
                events.dealPublished(it)
            }
            PlannedDeals(started, deleted)
        }

    private suspend fun plan(
        restaurant: Restaurant,
        now: Instant,
        settings: DealSettings,
        dealtToday: Set<Uuid>,
    ): Deal? {
        if (!restaurant.isOpenAt(now.atZone(BERLIN).toLocalTime())) return null
        val dish: Dish =
            dishes
                .ofRestaurant(restaurant.id)
                .filter { it.available && !it.archived && it.price.cents >= settings.minimumPriceCents && it.id !in dealtToday }
                .randomOrNull(random) ?: return null
        return Deal.forTheDay(dish, settings.percentages.random(random), now, BERLIN)
    }

    private fun startOfDay(now: Instant): Instant =
        now
            .atZone(BERLIN)
            .toLocalDate()
            .atStartOfDay(BERLIN)
            .toInstant()

    private companion object {
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
    }
}

/** A deal as the backoffice scheduled (or cancelled) it. */
data class DealSchedule(
    val dealId: Uuid,
    val dishId: Uuid,
    val percentOff: Int,
    val startsAt: Instant,
    val endsAt: Instant,
    val cancelled: Boolean,
)

/**
 * Deals an editor scheduled in the backoffice: priced from the dish as it is now, published like
 * planned ones. A cancelled deal keeps its id and its window closes, so every projection ends it.
 */
class ScheduleDeals(
    private val deals: Deals,
    private val dishes: Dishes,
    private val events: CatalogEvents,
    private val transactions: Transactions,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend operator fun invoke(schedule: DealSchedule): Deal? =
        transactions.run {
            val existing = deals.findById(schedule.dealId)
            val deal =
                if (schedule.cancelled) {
                    existing?.cancelledAt(clock.instant()) ?: return@run null
                } else {
                    val dish =
                        dishes.findById(schedule.dishId)
                            ?: throw AppError.NotFound(ErrorCode("DISH_NOT_FOUND"), "Dish ${schedule.dishId} does not exist")
                    try {
                        Deal.scheduled(schedule.dealId, dish, schedule.percentOff, schedule.startsAt, schedule.endsAt)
                    } catch (e: InvalidCatalogData) {
                        throw catalogInvalid(e.field, e.message)
                    }
                }
            if (deal != existing) {
                deals.upsert(deal)
                events.dealPublished(deal)
            }
            deal
        }
}

/** The planner's settings from the backoffice, checked before they replace the old ones. */
class UpdateDealPlanner(
    private val settings: DealPlannerSettings,
) {
    suspend operator fun invoke(update: DealSettings) {
        if (update.concurrent < 0) throw catalogInvalid("concurrent", "must not be negative")
        if (update.percentages.isEmpty() || update.percentages.any { it !in Deal.MIN_PERCENT..Deal.MAX_PERCENT }) {
            throw catalogInvalid("percentages", "needs percentages between ${Deal.MIN_PERCENT} and ${Deal.MAX_PERCENT}")
        }
        if (update.minimumPriceCents < 0) throw catalogInvalid("minimumPriceCents", "must not be negative")
        settings.save(update)
    }
}

/**
 * The backoffice's view of the deals (role `admin`): what runs now and what is scheduled. An editor
 * may end a running planner deal; deals scheduled in the backoffice are cancelled there, so the
 * backoffice never shows one as running that catalog has ended.
 */
class AdminDeals(
    private val deals: Deals,
    private val dishes: Dishes,
    private val restaurants: Restaurants,
    private val events: CatalogEvents,
    private val transactions: Transactions,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun current(): List<DealOffer> =
        deals.endingAfter(clock.instant()).mapNotNull { deal ->
            val dish = dishes.findById(deal.dishId) ?: return@mapNotNull null
            val restaurant = restaurants.findById(deal.restaurantId) ?: return@mapNotNull null
            DealOffer(deal, dish, restaurant)
        }

    suspend fun end(id: String): Deal =
        transactions.run {
            val deal = runCatching { Uuid.parse(id) }.getOrNull()?.let { deals.findById(it) } ?: throw dealNotFound()
            if (deal.source == DealSource.BACKOFFICE) {
                throw AppError.Conflict(ErrorCode("DEAL_FROM_BACKOFFICE"), "Deals scheduled in the backoffice are cancelled there")
            }
            val ended = deal.cancelledAt(clock.instant())
            if (ended != deal) {
                deals.upsert(ended)
                events.dealPublished(ended)
            }
            ended
        }

    private fun dealNotFound() = AppError.NotFound(ErrorCode("DEAL_NOT_FOUND"), "There is no such deal")
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
            val dish = dishes.findById(deal.dishId)?.takeIf { it.available && !it.archived } ?: return@mapNotNull null
            val restaurant = restaurants.findById(deal.restaurantId)?.takeIf { !it.archived } ?: return@mapNotNull null
            DealOffer(deal, dish, restaurant)
        }

    /** Running deals by dish id, for a menu or a dish page. */
    suspend fun byDish(): Map<Uuid, Deal> = deals.activeAt(clock.instant()).associateBy { it.dishId }
}
