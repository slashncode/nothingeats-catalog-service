package io.github.slashncode.nothingeats.catalog.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToLong
import kotlin.uuid.Uuid

/** Who made a deal: catalog's planner, or an editor in the backoffice (who also cancels it there). */
enum class DealSource { PLANNER, BACKOFFICE }

/**
 * A deal of the day: one dish costs less until midnight in Hamburg. There is no stock and no
 * "only 3 left": a restaurant always has food, so the only urgency is the clock. Planned by
 * catalog or scheduled in the backoffice; a cancelled deal keeps its id and its window closes.
 */
data class Deal(
    val id: Uuid,
    val dishId: Uuid,
    val restaurantId: Uuid,
    val percentOff: Int,
    val price: Money,
    val regularPrice: Money,
    val startsAt: Instant,
    val endsAt: Instant,
    val source: DealSource = DealSource.PLANNER,
) {
    init {
        if (percentOff !in MIN_PERCENT..MAX_PERCENT) throw InvalidCatalogData("percentOff", "must be between $MIN_PERCENT and $MAX_PERCENT")
        // An empty window (start = end) is a deal cancelled before it began: never active.
        if (endsAt.isBefore(startsAt)) throw InvalidCatalogData("endsAt", "must not be before startsAt")
        if (price.cents >= regularPrice.cents) throw InvalidCatalogData("price", "must be below the regular price")
    }

    fun isActiveAt(time: Instant): Boolean = !time.isBefore(startsAt) && time.isBefore(endsAt)

    /** Closes the window at [time]: at once if it runs, before it starts if it has not. */
    fun cancelledAt(time: Instant): Deal = copy(endsAt = maxOf(startsAt, minOf(endsAt, time)))

    companion object {
        const val MIN_PERCENT = 5
        const val MAX_PERCENT = 90

        fun of(
            dish: Dish,
            percentOff: Int,
            startsAt: Instant,
            duration: Duration,
            id: Uuid = Uuid.random(),
        ) = Deal(id, dish.id, dish.restaurantId, percentOff, discounted(dish.price, percentOff), dish.price, startsAt, startsAt + duration)

        /** The planner's deal: from [startsAt] until the next midnight in [zone]. */
        fun forTheDay(
            dish: Dish,
            percentOff: Int,
            startsAt: Instant,
            zone: ZoneId,
            id: Uuid = Uuid.random(),
        ) = of(dish, percentOff, startsAt, Duration.between(startsAt, nextMidnight(startsAt, zone)), id)

        fun nextMidnight(
            time: Instant,
            zone: ZoneId,
        ): Instant =
            time
                .atZone(zone)
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay(zone)
                .toInstant()

        /** A deal scheduled in the backoffice, priced from the dish as it is now. */
        fun scheduled(
            id: Uuid,
            dish: Dish,
            percentOff: Int,
            startsAt: Instant,
            endsAt: Instant,
        ): Deal {
            if (!endsAt.isAfter(startsAt)) throw InvalidCatalogData("endsAt", "must be after startsAt")
            return Deal(
                id,
                dish.id,
                dish.restaurantId,
                percentOff,
                discounted(dish.price, percentOff),
                dish.price,
                startsAt,
                endsAt,
                DealSource.BACKOFFICE,
            )
        }

        /** Rounded to whole ten cents, like a menu would print it. */
        fun discounted(
            regular: Money,
            percentOff: Int,
        ): Money = Money((regular.cents * (100 - percentOff) / 1000.0).roundToLong() * 10)
    }
}
