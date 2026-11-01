package io.github.slashncode.nothingeats.catalog.adapters.persistence

import io.github.slashncode.nothingeats.catalog.application.DealPlannerSettings
import io.github.slashncode.nothingeats.catalog.application.DealSettings
import io.github.slashncode.nothingeats.catalog.application.DishInput
import io.github.slashncode.nothingeats.catalog.application.ImageInput
import io.github.slashncode.nothingeats.catalog.application.PendingDishes
import io.github.slashncode.nothingeats.catalog.application.Translated
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock
import java.time.Duration
import kotlin.uuid.Uuid

internal object PendingDishesTable : Table("pending_dishes") {
    val id = uuid("id")
    val restaurantId = uuid("restaurant_id")
    val input = text("input")
    val receivedAt = timestamp("received_at")

    override val primaryKey = PrimaryKey(id)
}

internal object DealPlannerSettingsTable : Table("deal_planner_settings") {
    val id = integer("id")
    val enabled = bool("enabled")
    val concurrent = integer("concurrent")
    val durationsMinutes = array<Int>("durations_minutes", IntegerColumnType())
    val percentages = array<Int>("percentages", IntegerColumnType())
    val minimumPriceCents = long("minimum_price_cents")

    override val primaryKey = PrimaryKey(id)
}

@Serializable
private data class StoredText(
    val de: String,
    val en: String,
)

@Serializable
private data class StoredDishImage(
    val url: String,
    val cardUrl: String,
    val wideUrl: String,
    val width: Int? = null,
    val height: Int? = null,
    val focalX: Double? = null,
    val focalY: Double? = null,
    val alt: StoredText,
)

/** A waiting dish as it came, so applying it later is the same as applying it at once. */
@Serializable
private data class StoredDish(
    val slug: String,
    val section: StoredText,
    val name: StoredText,
    val description: StoredText,
    val priceCents: Long,
    val dietary: Set<String>,
    val allergens: Set<String>,
    val spiceLevel: Int,
    val calories: Int,
    val moods: Set<String>,
    val position: Int,
    val available: Boolean,
    val image: StoredDishImage? = null,
    val archived: Boolean = false,
)

private val json = Json { ignoreUnknownKeys = true }

private fun Translated.stored() = StoredText(de, en)

private fun StoredText.input() = Translated(de, en)

private fun DishInput.stored() =
    StoredDish(
        slug,
        section.stored(),
        name.stored(),
        description.stored(),
        priceCents,
        dietary,
        allergens,
        spiceLevel,
        calories,
        moods,
        position,
        available,
        image?.let { StoredDishImage(it.url, it.cardUrl, it.wideUrl, it.width, it.height, it.focalX, it.focalY, it.alt.stored()) },
        archived,
    )

private fun StoredDish.input() =
    DishInput(
        slug,
        section.input(),
        name.input(),
        description.input(),
        priceCents,
        dietary,
        allergens,
        spiceLevel,
        calories,
        moods,
        position,
        available,
        image?.let { ImageInput(it.url, it.cardUrl, it.wideUrl, it.width, it.height, it.focalX, it.focalY, it.alt.input()) },
        archived,
    )

class ExposedPendingDishes(
    private val db: PostgresDatabase,
    private val clock: Clock = Clock.systemUTC(),
) : PendingDishes {
    override suspend fun park(
        dishId: Uuid,
        restaurantId: Uuid,
        input: DishInput,
    ) {
        db.transaction {
            // A newer snapshot of the same dish replaces the waiting one.
            PendingDishesTable.upsert(PendingDishesTable.id) {
                it[id] = dishId
                it[PendingDishesTable.restaurantId] = restaurantId
                it[PendingDishesTable.input] = json.encodeToString(StoredDish.serializer(), input.stored())
                it[receivedAt] = clock.instant()
            }
        }
    }

    override suspend fun take(restaurantId: Uuid): List<Pair<Uuid, DishInput>> =
        db.transaction {
            val waiting =
                PendingDishesTable
                    .selectAll()
                    .where { PendingDishesTable.restaurantId eq restaurantId }
                    .forUpdate()
                    .map {
                        it[PendingDishesTable.id] to
                            json
                                .decodeFromString(
                                    StoredDish.serializer(),
                                    it[PendingDishesTable.input],
                                ).input()
                    }
            if (waiting.isNotEmpty()) PendingDishesTable.deleteWhere { PendingDishesTable.restaurantId eq restaurantId }
            waiting
        }
}

class ExposedDealPlannerSettings(
    private val db: PostgresDatabase,
) : DealPlannerSettings {
    override suspend fun current(): DealSettings? =
        db.transaction(readOnly = true) {
            DealPlannerSettingsTable.selectAll().singleOrNull()?.let {
                DealSettings(
                    enabled = it[DealPlannerSettingsTable.enabled],
                    concurrent = it[DealPlannerSettingsTable.concurrent],
                    durations = it[DealPlannerSettingsTable.durationsMinutes].map { minutes -> Duration.ofMinutes(minutes.toLong()) },
                    percentages = it[DealPlannerSettingsTable.percentages],
                    minimumPriceCents = it[DealPlannerSettingsTable.minimumPriceCents],
                )
            }
        }

    override suspend fun save(settings: DealSettings) {
        db.transaction {
            DealPlannerSettingsTable.upsert(DealPlannerSettingsTable.id) {
                it[id] = 1
                it[enabled] = settings.enabled
                it[concurrent] = settings.concurrent
                it[durationsMinutes] = settings.durations.map { duration -> duration.toMinutes().toInt() }
                it[percentages] = settings.percentages
                it[minimumPriceCents] = settings.minimumPriceCents
            }
        }
    }
}
