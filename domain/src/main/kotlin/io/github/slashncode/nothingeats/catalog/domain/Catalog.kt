package io.github.slashncode.nothingeats.catalog.domain

import java.time.LocalTime
import kotlin.uuid.Uuid

enum class Language(
    val tag: String,
) {
    DE("de"),
    EN("en"),
    ;

    companion object {
        /** Accepts `de`, `en-GB` or an Accept-Language header; anything unknown is German. */
        fun parse(raw: String?): Language {
            val first =
                raw
                    ?.split(',')
                    ?.firstOrNull()
                    ?.trim()
                    ?.lowercase()
                    ?.take(2)
            return entries.firstOrNull { it.tag == first } ?: DE
        }
    }
}

/** Customer-facing text in every supported language. */
data class LocalizedText(
    val de: String,
    val en: String,
) {
    init {
        if (de.isBlank() || en.isBlank()) throw InvalidCatalogData("text", "needs German and English")
    }

    fun of(language: Language) =
        when (language) {
            Language.DE -> de
            Language.EN -> en
        }
}

/** Euro cents, never floating point. */
@JvmInline
value class Money(
    val cents: Long,
) {
    init {
        if (cents < 0) throw InvalidCatalogData("price", "must not be negative")
    }
}

/** URL-friendly identifier, stable across renames of the display name. */
@JvmInline
value class Slug(
    val value: String,
) {
    init {
        if (value.length !in 2..60 || !PATTERN.matches(value)) {
            throw InvalidCatalogData("slug", "must be 2-60 lowercase letters, digits or dashes")
        }
    }

    override fun toString() = value

    private companion object {
        val PATTERN = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    }
}

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) throw InvalidCatalogData("location", "is outside the globe")
    }
}

enum class DietaryTag { VEGAN, VEGETARIAN, GLUTEN_FREE, LACTOSE_FREE, HALAL }

/** The 14 allergens that EU law requires restaurants to declare. */
enum class Allergen { GLUTEN, CRUSTACEANS, EGGS, FISH, PEANUTS, SOYBEANS, MILK, NUTS, CELERY, MUSTARD, SESAME, SULPHITES, LUPIN, MOLLUSCS }

data class Restaurant(
    val id: Uuid,
    val slug: Slug,
    val name: String,
    val description: LocalizedText,
    val cuisine: Slug,
    val rating: Double,
    val deliveryMinutes: Int,
    val deliveryFee: Money,
    val minimumOrder: Money,
    val location: GeoPoint,
    val opensAt: LocalTime,
    val closesAt: LocalTime,
) {
    init {
        if (name.isBlank()) throw InvalidCatalogData("name", "must not be blank")
        if (rating !in 0.0..5.0) throw InvalidCatalogData("rating", "must be between 0 and 5")
        if (deliveryMinutes !in 1..180) throw InvalidCatalogData("deliveryMinutes", "must be between 1 and 180")
    }

    /** Handles opening hours that run past midnight (e.g. 18:00–02:00). */
    fun isOpenAt(time: LocalTime): Boolean =
        if (opensAt <= closesAt) {
            time >= opensAt && time < closesAt
        } else {
            time >= opensAt || time < closesAt
        }
}

data class Dish(
    val id: Uuid,
    val restaurantId: Uuid,
    val slug: Slug,
    val section: LocalizedText,
    val name: LocalizedText,
    val description: LocalizedText,
    val price: Money,
    val dietary: Set<DietaryTag>,
    val allergens: Set<Allergen>,
    val spiceLevel: Int,
    val calories: Int,
    /** Cravings the dish satisfies (crispy, cheesy, comfort, …), the vocabulary of the AI chat. */
    val moods: Set<String>,
    val position: Int,
    val available: Boolean,
) {
    init {
        if (spiceLevel !in 0..3) throw InvalidCatalogData("spiceLevel", "must be between 0 and 3")
        if (calories !in 0..5000) throw InvalidCatalogData("calories", "must be between 0 and 5000")
        if (DietaryTag.VEGAN in dietary && (Allergen.MILK in allergens || Allergen.EGGS in allergens)) {
            throw InvalidCatalogData("dietary", "a vegan dish cannot contain milk or eggs")
        }
    }
}

class InvalidCatalogData(
    val field: String,
    reason: String,
) : Exception(reason)
