package io.github.slashncode.nothingeats.catalog.adapters.seed

import io.github.slashncode.nothingeats.catalog.application.DirectTransactions
import io.github.slashncode.nothingeats.catalog.application.InMemoryDishes
import io.github.slashncode.nothingeats.catalog.application.InMemoryRestaurants
import io.github.slashncode.nothingeats.catalog.application.LoadSeed
import io.github.slashncode.nothingeats.catalog.application.ManageCatalog
import io.github.slashncode.nothingeats.catalog.application.RecordingCatalogEvents
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class LoadSeedTaskTest :
    FunSpec({
        test("the bundled seed is valid, and loading it twice changes nothing") {
            val restaurants = InMemoryRestaurants()
            val dishes = InMemoryDishes()
            val events = RecordingCatalogEvents()
            val task = LoadSeedTask(LoadSeed(ManageCatalog(restaurants, dishes, events, DirectTransactions())))

            task.run()
            task.run()

            restaurants.all.values shouldHaveSize 8
            dishes.all.values shouldHaveSize 48
            restaurants.all.values.forEach { restaurant -> dishes.all.values.count { it.restaurantId == restaurant.id } shouldBe 6 }
            events.events shouldHaveSize 8 + 48
        }
    })
