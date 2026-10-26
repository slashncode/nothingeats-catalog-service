package io.github.slashncode.nothingeats.catalog.domain

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong
import kotlin.uuid.Uuid

/**
 * A flash deal: one dish costs less for a short while. There is no stock and no "only 3 left":
 * a restaurant always has food, so the only urgency is the clock.
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
) {
    init {
        if (percentOff !in MIN_PERCENT..MAX_PERCENT) throw InvalidCatalogData("percentOff", "must be between $MIN_PERCENT and $MAX_PERCENT")
        if (!endsAt.isAfter(startsAt)) throw InvalidCatalogData("endsAt", "must be after startsAt")
        if (price.cents >= regularPrice.cents) throw InvalidCatalogData("price", "must be below the regular price")
    }

    fun isActiveAt(time: Instant): Boolean = !time.isBefore(startsAt) && time.isBefore(endsAt)

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

        /** Rounded to whole ten cents, like a menu would print it. */
        fun discounted(
            regular: Money,
            percentOff: Int,
        ): Money = Money((regular.cents * (100 - percentOff) / 1000.0).roundToLong() * 10)
    }
}
