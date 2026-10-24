package io.github.slashncode.nothingeats.catalog.adapters.seed

import io.github.slashncode.nothingeats.catalog.application.DishInput
import io.github.slashncode.nothingeats.catalog.application.LoadSeed
import io.github.slashncode.nothingeats.catalog.application.RestaurantInput
import io.github.slashncode.nothingeats.catalog.application.SeedRestaurant
import io.github.slashncode.nothingeats.catalog.application.Translated
import io.github.slashncode.nothingeats.platform.core.run.CronTask
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
private data class SeedText(
    val de: String,
    val en: String,
)

@Serializable
private data class SeedDish(
    val slug: String,
    val section: SeedText,
    val name: SeedText,
    val description: SeedText,
    val priceCents: Long,
    val dietary: Set<String> = emptySet(),
    val allergens: Set<String> = emptySet(),
    val spiceLevel: Int = 0,
    val calories: Int,
    val moods: Set<String> = emptySet(),
)

@Serializable
private data class SeedEntry(
    val slug: String,
    val name: String,
    val description: SeedText,
    val cuisine: String,
    val rating: Double,
    val deliveryMinutes: Int,
    val deliveryFeeCents: Long,
    val minimumOrderCents: Long,
    val latitude: Double,
    val longitude: Double,
    val opensAt: String,
    val closesAt: String,
    val dishes: List<SeedDish>,
)

private fun SeedText.input() = Translated(de, en)

/** `cron load-seed`: imports `seed/catalog.json` from the classpath. Idempotent. */
class LoadSeedTask(
    private val loadSeed: LoadSeed,
    private val resource: String = "/seed/catalog.json",
) : CronTask {
    override val name = "load-seed"

    private val log = LoggerFactory.getLogger(LoadSeedTask::class.java)

    override suspend fun run() {
        val json = LoadSeedTask::class.java.getResource(resource)?.readText() ?: error("Seed $resource is missing")
        val entries = Json.decodeFromString<List<SeedEntry>>(json)
        val dishes =
            loadSeed(
                entries.map { entry ->
                    SeedRestaurant(
                        RestaurantInput(
                            slug = entry.slug,
                            name = entry.name,
                            description = entry.description.input(),
                            cuisine = entry.cuisine,
                            rating = entry.rating,
                            deliveryMinutes = entry.deliveryMinutes,
                            deliveryFeeCents = entry.deliveryFeeCents,
                            minimumOrderCents = entry.minimumOrderCents,
                            latitude = entry.latitude,
                            longitude = entry.longitude,
                            opensAt = entry.opensAt,
                            closesAt = entry.closesAt,
                        ),
                        entry.dishes.mapIndexed { index, dish ->
                            DishInput(
                                slug = dish.slug,
                                section = dish.section.input(),
                                name = dish.name.input(),
                                description = dish.description.input(),
                                priceCents = dish.priceCents,
                                dietary = dish.dietary,
                                allergens = dish.allergens,
                                spiceLevel = dish.spiceLevel,
                                calories = dish.calories,
                                moods = dish.moods,
                                position = index,
                            )
                        },
                    )
                },
            )
        log.info("Seed loaded: {} restaurants, {} dishes", entries.size, dishes)
    }
}
