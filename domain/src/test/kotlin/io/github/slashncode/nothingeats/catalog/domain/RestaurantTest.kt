package io.github.slashncode.nothingeats.catalog.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalTime
import kotlin.uuid.Uuid

private fun restaurant(
    opens: String,
    closes: String,
) = Restaurant(
    id = Uuid.random(),
    slug = Slug("night-owl"),
    name = "Night Owl",
    description = LocalizedText("Spät", "Late"),
    cuisine = Slug("burger"),
    rating = 4.0,
    deliveryMinutes = 30,
    deliveryFee = Money(0),
    minimumOrder = Money(0),
    location = GeoPoint(53.5, 10.0),
    opensAt = LocalTime.parse(opens),
    closesAt = LocalTime.parse(closes),
)

class RestaurantTest :
    FunSpec({
        test("opening hours within a day") {
            val lunch = restaurant("11:00", "15:00")

            lunch.isOpenAt(LocalTime.parse("11:00")) shouldBe true
            lunch.isOpenAt(LocalTime.parse("15:00")) shouldBe false
        }

        test("opening hours past midnight") {
            val late = restaurant("18:00", "02:00")

            late.isOpenAt(LocalTime.parse("23:30")) shouldBe true
            late.isOpenAt(LocalTime.parse("01:59")) shouldBe true
            late.isOpenAt(LocalTime.parse("12:00")) shouldBe false
        }

        test("languages come from tags or Accept-Language headers") {
            Language.parse("en-GB,en;q=0.9,de;q=0.8") shouldBe Language.EN
            Language.parse(null) shouldBe Language.DE
        }
    })
