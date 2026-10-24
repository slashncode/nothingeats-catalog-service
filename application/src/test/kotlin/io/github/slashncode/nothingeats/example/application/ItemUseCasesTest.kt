package io.github.slashncode.nothingeats.example.application

import io.github.slashncode.nothingeats.example.domain.ItemId
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.Violation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ItemUseCasesTest :
    FunSpec({
        val now = Instant.parse("2026-10-24T12:00:00.123456789Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)

        test("creates an item with a trimmed name") {
            val items = InMemoryItemRepository()

            val item = CreateItem(items, clock)("  Phantom Pizza ")

            item.name.value shouldBe "Phantom Pizza"
            item.createdAt shouldBe Instant.parse("2026-10-24T12:00:00.123456Z")
            GetItem(items)(item.id) shouldBe item
        }

        test("invalid names become a validation error on the name field") {
            val error = shouldThrow<AppError.Invalid> { CreateItem(InMemoryItemRepository(), clock)(" ") }

            error.code.value shouldBe "ITEM_INVALID"
            error.violations shouldBe listOf(Violation("name", "must not be blank"))
        }

        test("a taken name is a conflict") {
            val items = InMemoryItemRepository()
            CreateItem(items, clock)("Phantom Pizza")

            shouldThrow<AppError.Conflict> { CreateItem(items, clock)("Phantom Pizza") }.code.value shouldBe "ITEM_NAME_TAKEN"
        }

        test("an unknown id is not found") {
            shouldThrow<AppError.NotFound> { GetItem(InMemoryItemRepository())(ItemId.random()) }.code.value shouldBe "ITEM_NOT_FOUND"
        }
    })
