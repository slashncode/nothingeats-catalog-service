package io.github.slashncode.nothingeats.catalog.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.uuid.Uuid

class DealTest :
    FunSpec({
        test("deal prices are rounded to ten cents") {
            Deal.discounted(Money(1290), 30) shouldBe Money(900)
            Deal.discounted(Money(990), 25) shouldBe Money(740)
            Deal.discounted(Money(500), 40) shouldBe Money(300)
        }

        test("a deal runs from its start until just before its end") {
            val start = Instant.parse("2026-11-02T11:00:00Z")
            val deal =
                Deal(
                    Uuid.random(),
                    Uuid.random(),
                    Uuid.random(),
                    30,
                    Money(900),
                    Money(1290),
                    start,
                    start.plusSeconds(60),
                )

            deal.isActiveAt(start.minusSeconds(1)) shouldBe false
            deal.isActiveAt(start) shouldBe true
            deal.isActiveAt(start.plusSeconds(59)) shouldBe true
            deal.isActiveAt(start.plusSeconds(60)) shouldBe false
        }

        test("a deal must be cheaper than the dish") {
            val start = Instant.parse("2026-11-02T11:00:00Z")
            shouldThrow<InvalidCatalogData> {
                Deal(Uuid.random(), Uuid.random(), Uuid.random(), 10, Money(1290), Money(1290), start, start.plusSeconds(60))
            }
        }
    })
