# nothingeats-catalog-service

Catalog bounded context of [NothingEats](https://github.com/slashncode/nothingeats): restaurants, dishes and menus in German and English, and flash deals. Deals have no stock: a restaurant always has food, so the only urgency is the clock.

The content is edited in the [backoffice](https://github.com/slashncode/nothingeats-backoffice) (Payload), which publishes it through its own outbox. Catalog consumes `backoffice.restaurant.v1`, `backoffice.dish.v1`, `backoffice.deal.v1` and `backoffice.deal-planner.v1`, checks the content against its domain rules, and stays the only producer of the `catalog.*` events everything downstream reads.

## API (`/api/catalog/v1`)

| Method | Path | Auth | What |
|---|---|---|---|
| `GET` | `/restaurants?cursor=&limit=&cuisine=` | – | Restaurants ordered by slug, cursor-paginated (`limit` 1–50, default 20), optionally one cuisine |
| `GET` | `/restaurants/{slug}` | – | The restaurant with its menu, dishes grouped by section |
| `GET` | `/dishes/{id}` | – | One dish with its restaurant |
| `GET` | `/deals` | – | Running flash deals with dish and restaurant, soonest end first |

The API only reads; changes arrive as backoffice events.

- **Language**: `?lang=de|en` wins over `Accept-Language`; anything else is German. Responses carry `Content-Language` and `Vary: Accept-Language`.
- **Open now**: computed per request in Berlin time; opening hours may run past midnight (`18:00`–`02:00`).
- **Flash deals**: editors schedule deals in the backoffice; a loop on every pod (one at a time, via a Postgres advisory lock) fills up to the planner's number of deals (default three, editable in the backoffice) at other restaurants, only where the kitchen stays open for the whole deal. A cancelled deal keeps its id and its window closes. Dishes in menus and `/dishes/{id}` carry `deal` (`priceCents`, `percentOff`, `endsAt`) while one runs.
- **Images**: restaurants and dishes carry `image` (`url`, `cardUrl`, `wideUrl`, `alt` in the requested language, size, focal point) when the backoffice has one; dishes without one get the shop's illustration.
- **Archived** restaurants and dishes are gone for customers (pages, menus, dishes, deals) but kept, because past orders point at them.
- **Money** is always in euro cents. Allergens are the 14 EU declarable allergens, dietary tags are `vegan`, `vegetarian`, `gluten_free`, `lactose_free`, `halal`.
- Errors are RFC 9457 problems with stable codes: `RESTAURANT_NOT_FOUND`, `DISH_NOT_FOUND`, `CURSOR_INVALID`, `CATALOG_INVALID` (with the offending field).

Every change that actually changes something is published through the transactional outbox, with both languages in the payload: see [docs/events.md](docs/events.md). A snapshot that changes nothing publishes nothing, so a replayed backoffice topic is quiet. A dish that overtakes its restaurant waits in `pending_dishes` and is applied when the restaurant arrives; content that breaks a rule lands in `catalog-service.dlq`.

## Demo data

The starting content (eight restaurants in Hamburg with six dishes each) lives in the backoffice now (`payload seed`); catalog gets it as events like any other change.

## Run

```bash
./gradlew build                     # compile, ktlint, tests (Docker needed for Testcontainers)

export DATABASE__URL=jdbc:postgresql://localhost:15432/catalog DATABASE__USER=catalog DATABASE__PASSWORD=...
./gradlew :app:run --args=migrate
./gradlew :app:run

# in the k3d cluster, from the hub repo (Tilt, live rebuilds; stop with just dev-stop catalog-service)
just dev catalog-service
```

## License

MIT
