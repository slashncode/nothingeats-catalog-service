package io.github.slashncode.nothingeats.catalog.adapters.persistence

import io.github.slashncode.nothingeats.catalog.application.ManageCatalog
import io.github.slashncode.nothingeats.catalog.application.RecordingCatalogEvents
import io.github.slashncode.nothingeats.catalog.application.dishInput
import io.github.slashncode.nothingeats.catalog.application.restaurantInput
import io.github.slashncode.nothingeats.catalog.domain.Deal
import io.github.slashncode.nothingeats.platform.persistence.FlywayMigrations
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import io.github.slashncode.nothingeats.platform.testing.PostgresTestDatabases
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Duration
import java.time.Instant

class ExposedDealsTest :
    FunSpec({
        lateinit var database: PostgresDatabase

        beforeTest {
            val config = PostgresTestDatabases.create()
            FlywayMigrations(config).migrate()
            database = PostgresDatabase(config).also { it.start() }
        }

        afterTest { database.stop() }

        test("deals round-trip, only running ones are active, ended ones can be deleted") {
            val manage =
                ManageCatalog(
                    ExposedRestaurants(database),
                    ExposedDishes(database),
                    RecordingCatalogEvents(),
                    ExposedTransactions(database),
                )
            manage.upsertRestaurant(restaurantInput())
            val dish = manage.upsertDish("phantom-pizza", dishInput())
            val deals = ExposedDeals(database)
            val noon = Instant.parse("2026-11-02T11:00:00Z")
            val running = Deal.of(dish, 30, noon, Duration.ofMinutes(45))
            val ended = Deal.of(dish, 20, noon.minusSeconds(7200), Duration.ofMinutes(30))
            deals.insert(running)
            deals.insert(ended)

            deals.activeAt(noon.plusSeconds(60)) shouldContainExactly listOf(running)
            deals.deleteEndedBefore(noon) shouldBe 1
            deals.activeAt(noon.minusSeconds(5400)) shouldContainExactly emptyList()
        }

        test("only one transaction at a time gets the planning lock") {
            val deals = ExposedDeals(database)
            val transactions = ExposedTransactions(database)
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            coroutineScope {
                val first =
                    async {
                        transactions.run {
                            val locked = deals.tryLockPlanning()
                            held.complete(Unit)
                            release.await()
                            locked
                        }
                    }
                held.await()
                val second = transactions.run { deals.tryLockPlanning() }
                release.complete(Unit)

                first.await() shouldBe true
                second shouldBe false
            }
            transactions.run { deals.tryLockPlanning() } shouldBe true
        }
    })
