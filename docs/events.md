# Events: `catalog.restaurant.v1` and `catalog.dish.v1`

CloudEvents in structured mode (`application/cloudevents+json`), `source` = `/nothingeats/catalog`,
record key and `subject` = the restaurant or dish id (ordering per aggregate). Consumers must ignore unknown fields.

Every event is a full snapshot in all languages, so projections (search, ordering prices) never call back
into the catalog. An upsert that changes nothing publishes nothing; re-running the seed import is quiet.

## `catalog.restaurant.v1`

| `type` | When | `data` |
|---|---|---|
| `nothingeats.catalog.restaurant.published.v1` | A restaurant was created or changed | `restaurantId`, `slug`, `name`, `description` (`{de, en}`), `cuisine`, `rating`, `deliveryMinutes`, `deliveryFeeCents`, `minimumOrderCents`, `latitude`, `longitude`, `opensAt`, `closesAt` (`HH:mm`, Berlin time) |

## `catalog.dish.v1`

| `type` | When | `data` |
|---|---|---|
| `nothingeats.catalog.dish.published.v1` | A dish was created or changed | `dishId`, `restaurantId`, `restaurantSlug`, `restaurantName`, `cuisine`, `slug`, `section`, `name`, `description` (each `{de, en}`), `priceCents`, `dietary`, `allergens` (lowercase, sorted), `spiceLevel` (0–3), `calories`, `moods`, `available` |

The dish event carries the restaurant's slug, name and cuisine as they were when the dish changed. A
restaurant rename does not republish its dishes; projections that show the restaurant name join on
`restaurantId` with the restaurant topic.
