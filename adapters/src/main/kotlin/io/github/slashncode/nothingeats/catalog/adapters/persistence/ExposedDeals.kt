package io.github.slashncode.nothingeats.catalog.adapters.persistence

import io.github.slashncode.nothingeats.catalog.application.Deals
import io.github.slashncode.nothingeats.catalog.domain.Deal
import io.github.slashncode.nothingeats.catalog.domain.Money
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant

internal object DealsTable : Table("deals") {
    val id = uuid("id")
    val dishId = uuid("dish_id")
    val restaurantId = uuid("restaurant_id")
    val percentOff = integer("percent_off")
    val priceCents = long("price_cents")
    val regularPriceCents = long("regular_price_cents")
    val startsAt = timestamp("starts_at")
    val endsAt = timestamp("ends_at")

    override val primaryKey = PrimaryKey(id)
}

class ExposedDeals(
    private val db: PostgresDatabase,
) : Deals {
    override suspend fun insert(deal: Deal) {
        db.transaction {
            DealsTable.insert {
                it[id] = deal.id
                it[dishId] = deal.dishId
                it[restaurantId] = deal.restaurantId
                it[percentOff] = deal.percentOff
                it[priceCents] = deal.price.cents
                it[regularPriceCents] = deal.regularPrice.cents
                it[startsAt] = deal.startsAt
                it[endsAt] = deal.endsAt
            }
        }
    }

    override suspend fun activeAt(time: Instant): List<Deal> =
        db.transaction(readOnly = true) {
            DealsTable
                .selectAll()
                .where { (DealsTable.startsAt lessEq time) and (DealsTable.endsAt greater time) }
                .orderBy(DealsTable.endsAt to SortOrder.ASC)
                .map { it.toDeal() }
        }

    override suspend fun deleteEndedBefore(time: Instant): Int = db.transaction { DealsTable.deleteWhere { DealsTable.endsAt less time } }

    // A transaction-scoped advisory lock: released with the transaction, never left behind by a dead pod.
    override suspend fun tryLockPlanning(): Boolean =
        db.transaction {
            exec("select pg_try_advisory_xact_lock($PLANNING_LOCK)") { result ->
                result.next() && result.getBoolean(1)
            } ?: false
        }

    private fun ResultRow.toDeal() =
        Deal(
            id = this[DealsTable.id],
            dishId = this[DealsTable.dishId],
            restaurantId = this[DealsTable.restaurantId],
            percentOff = this[DealsTable.percentOff],
            price = Money(this[DealsTable.priceCents]),
            regularPrice = Money(this[DealsTable.regularPriceCents]),
            startsAt = this[DealsTable.startsAt],
            endsAt = this[DealsTable.endsAt],
        )

    private companion object {
        /** Any fixed number, unique among this database's advisory locks. */
        const val PLANNING_LOCK = 4_711_001L
    }
}
