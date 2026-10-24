package io.github.slashncode.nothingeats.catalog.application

import io.github.slashncode.nothingeats.catalog.domain.InvalidItemName
import io.github.slashncode.nothingeats.catalog.domain.Item
import io.github.slashncode.nothingeats.catalog.domain.ItemId
import io.github.slashncode.nothingeats.catalog.domain.ItemName
import io.github.slashncode.nothingeats.catalog.domain.ItemNameTaken
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import io.github.slashncode.nothingeats.platform.core.error.Violation
import java.time.Clock
import java.time.temporal.ChronoUnit

class CreateItem(
    private val items: ItemRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(name: String): Item {
        val item =
            Item(
                id = ItemId.random(),
                name = parseName(name),
                // Postgres keeps microseconds; truncate so the response matches what is stored.
                createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
            )
        try {
            items.add(item)
        } catch (e: ItemNameTaken) {
            throw AppError.Conflict(ErrorCode("ITEM_NAME_TAKEN"), e.message ?: "Name taken", e)
        }
        return item
    }

    private fun parseName(raw: String): ItemName =
        try {
            ItemName(raw.trim())
        } catch (e: InvalidItemName) {
            throw AppError.Invalid(ErrorCode("ITEM_INVALID"), "The item is invalid", listOf(Violation("name", e.message ?: "invalid")))
        }
}

class GetItem(
    private val items: ItemRepository,
) {
    suspend operator fun invoke(id: ItemId): Item =
        items.find(id) ?: throw AppError.NotFound(ErrorCode("ITEM_NOT_FOUND"), "Item $id does not exist")
}
