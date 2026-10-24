package io.github.slashncode.nothingeats.catalog.application

import io.github.slashncode.nothingeats.catalog.domain.Item
import io.github.slashncode.nothingeats.catalog.domain.ItemId

/** Port for storing items; implemented in the adapters module. */
interface ItemRepository {
    /** @throws io.github.slashncode.nothingeats.catalog.domain.ItemNameTaken if the name is already used. */
    suspend fun add(item: Item)

    suspend fun find(id: ItemId): Item?
}
