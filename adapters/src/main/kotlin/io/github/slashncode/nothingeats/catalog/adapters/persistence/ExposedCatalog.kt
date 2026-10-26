package io.github.slashncode.nothingeats.catalog.adapters.persistence

import io.github.slashncode.nothingeats.catalog.application.Dishes
import io.github.slashncode.nothingeats.catalog.application.Restaurants
import io.github.slashncode.nothingeats.catalog.application.Transactions
import io.github.slashncode.nothingeats.catalog.domain.Allergen
import io.github.slashncode.nothingeats.catalog.domain.DietaryTag
import io.github.slashncode.nothingeats.catalog.domain.Dish
import io.github.slashncode.nothingeats.catalog.domain.GeoPoint
import io.github.slashncode.nothingeats.catalog.domain.LocalizedText
import io.github.slashncode.nothingeats.catalog.domain.Money
import io.github.slashncode.nothingeats.catalog.domain.Restaurant
import io.github.slashncode.nothingeats.catalog.domain.Slug
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.javatime.time
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.uuid.Uuid

internal object RestaurantsTable : Table("restaurants") {
    val id = uuid("id")
    val slug = text("slug")
    val name = text("name")
    val descriptionDe = text("description_de")
    val descriptionEn = text("description_en")
    val cuisine = text("cuisine")
    val rating = double("rating")
    val deliveryMinutes = integer("delivery_minutes")
    val deliveryFeeCents = long("delivery_fee_cents")
    val minimumOrderCents = long("minimum_order_cents")
    val latitude = double("latitude")
    val longitude = double("longitude")
    val opensAt = time("opens_at")
    val closesAt = time("closes_at")

    override val primaryKey = PrimaryKey(id)
}

internal object DishesTable : Table("dishes") {
    val id = uuid("id")
    val restaurantId = uuid("restaurant_id")
    val slug = text("slug")
    val sectionDe = text("section_de")
    val sectionEn = text("section_en")
    val nameDe = text("name_de")
    val nameEn = text("name_en")
    val descriptionDe = text("description_de")
    val descriptionEn = text("description_en")
    val priceCents = long("price_cents")
    val dietary = array<String>("dietary", TextColumnType())
    val allergens = array<String>("allergens", TextColumnType())
    val spiceLevel = integer("spice_level")
    val calories = integer("calories")
    val moods = array<String>("moods", TextColumnType())
    val position = integer("position")
    val available = bool("available")

    override val primaryKey = PrimaryKey(id)
}

class ExposedTransactions(
    private val db: PostgresDatabase,
) : Transactions {
    override suspend fun <T> run(block: suspend () -> T): T = db.transaction { block() }
}

class ExposedRestaurants(
    private val db: PostgresDatabase,
) : Restaurants {
    override suspend fun upsert(restaurant: Restaurant) {
        db.transaction {
            RestaurantsTable.upsert(RestaurantsTable.id) {
                it[id] = restaurant.id
                it[slug] = restaurant.slug.value
                it[name] = restaurant.name
                it[descriptionDe] = restaurant.description.de
                it[descriptionEn] = restaurant.description.en
                it[cuisine] = restaurant.cuisine.value
                it[rating] = restaurant.rating
                it[deliveryMinutes] = restaurant.deliveryMinutes
                it[deliveryFeeCents] = restaurant.deliveryFee.cents
                it[minimumOrderCents] = restaurant.minimumOrder.cents
                it[latitude] = restaurant.location.latitude
                it[longitude] = restaurant.location.longitude
                it[opensAt] = restaurant.opensAt
                it[closesAt] = restaurant.closesAt
            }
        }
    }

    override suspend fun findBySlug(slug: Slug): Restaurant? = one { RestaurantsTable.slug eq slug.value }

    override suspend fun all(): List<Restaurant> =
        db.transaction(readOnly = true) {
            RestaurantsTable
                .selectAll()
                .orderBy(RestaurantsTable.slug to SortOrder.ASC)
                .map { it.toRestaurant() }
        }

    override suspend fun findById(id: Uuid): Restaurant? = one { RestaurantsTable.id eq id }

    override suspend fun page(
        after: Slug?,
        limit: Int,
        cuisine: Slug?,
    ): List<Restaurant> =
        db.transaction(readOnly = true) {
            val conditions =
                listOfNotNull<Op<Boolean>>(
                    after?.let { RestaurantsTable.slug greater it.value },
                    cuisine?.let { RestaurantsTable.cuisine eq it.value },
                )
            RestaurantsTable
                .selectAll()
                .apply { if (conditions.isNotEmpty()) where { conditions.reduce { a, b -> a and b } } }
                .orderBy(RestaurantsTable.slug to SortOrder.ASC)
                .limit(limit)
                .map { it.toRestaurant() }
        }

    private suspend fun one(condition: () -> Op<Boolean>): Restaurant? =
        db.transaction(readOnly = true) {
            RestaurantsTable
                .selectAll()
                .where(condition)
                .singleOrNull()
                ?.toRestaurant()
        }

    private fun ResultRow.toRestaurant() =
        Restaurant(
            id = this[RestaurantsTable.id],
            slug = Slug(this[RestaurantsTable.slug]),
            name = this[RestaurantsTable.name],
            description = LocalizedText(this[RestaurantsTable.descriptionDe], this[RestaurantsTable.descriptionEn]),
            cuisine = Slug(this[RestaurantsTable.cuisine]),
            rating = this[RestaurantsTable.rating],
            deliveryMinutes = this[RestaurantsTable.deliveryMinutes],
            deliveryFee = Money(this[RestaurantsTable.deliveryFeeCents]),
            minimumOrder = Money(this[RestaurantsTable.minimumOrderCents]),
            location = GeoPoint(this[RestaurantsTable.latitude], this[RestaurantsTable.longitude]),
            opensAt = this[RestaurantsTable.opensAt],
            closesAt = this[RestaurantsTable.closesAt],
        )
}

class ExposedDishes(
    private val db: PostgresDatabase,
) : Dishes {
    override suspend fun upsert(dish: Dish) {
        db.transaction {
            DishesTable.upsert(DishesTable.id) {
                it[id] = dish.id
                it[restaurantId] = dish.restaurantId
                it[slug] = dish.slug.value
                it[sectionDe] = dish.section.de
                it[sectionEn] = dish.section.en
                it[nameDe] = dish.name.de
                it[nameEn] = dish.name.en
                it[descriptionDe] = dish.description.de
                it[descriptionEn] = dish.description.en
                it[priceCents] = dish.price.cents
                it[dietary] = dish.dietary.map { tag -> tag.name }.sorted()
                it[allergens] = dish.allergens.map { allergen -> allergen.name }.sorted()
                it[spiceLevel] = dish.spiceLevel
                it[calories] = dish.calories
                it[moods] = dish.moods.sorted()
                it[position] = dish.position
                it[available] = dish.available
            }
        }
    }

    override suspend fun findById(id: Uuid): Dish? =
        db.transaction(readOnly = true) {
            DishesTable
                .selectAll()
                .where { DishesTable.id eq id }
                .singleOrNull()
                ?.toDish()
        }

    override suspend fun findBySlug(
        restaurantId: Uuid,
        slug: Slug,
    ): Dish? =
        db.transaction(readOnly = true) {
            DishesTable
                .selectAll()
                .where { (DishesTable.restaurantId eq restaurantId) and (DishesTable.slug eq slug.value) }
                .singleOrNull()
                ?.toDish()
        }

    override suspend fun ofRestaurant(restaurantId: Uuid): List<Dish> =
        db.transaction(readOnly = true) {
            DishesTable
                .selectAll()
                .where { DishesTable.restaurantId eq restaurantId }
                .map { it.toDish() }
        }

    private fun ResultRow.toDish() =
        Dish(
            id = this[DishesTable.id],
            restaurantId = this[DishesTable.restaurantId],
            slug = Slug(this[DishesTable.slug]),
            section = LocalizedText(this[DishesTable.sectionDe], this[DishesTable.sectionEn]),
            name = LocalizedText(this[DishesTable.nameDe], this[DishesTable.nameEn]),
            description = LocalizedText(this[DishesTable.descriptionDe], this[DishesTable.descriptionEn]),
            price = Money(this[DishesTable.priceCents]),
            dietary = this[DishesTable.dietary].map { DietaryTag.valueOf(it) }.toSet(),
            allergens = this[DishesTable.allergens].map { Allergen.valueOf(it) }.toSet(),
            spiceLevel = this[DishesTable.spiceLevel],
            calories = this[DishesTable.calories],
            moods = this[DishesTable.moods].toSet(),
            position = this[DishesTable.position],
            available = this[DishesTable.available],
        )
}
