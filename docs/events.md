# Events: `catalog.restaurant.v1`, `catalog.dish.v1` and `catalog.deal.v1`

CloudEvents in structured mode (`application/cloudevents+json`), `source` = `/nothingeats/catalog`,
record key and `subject` = the restaurant, dish or deal id (ordering per aggregate). Consumers must ignore unknown fields.

Every event is a full snapshot in all languages, so projections (search, ordering prices) never call back
into the catalog. An update that changes nothing publishes nothing; a replayed backoffice topic is quiet.
Ids are the backoffice's (nothingeats-backoffice, record 040), where the content is edited.

`image` is `null` or `{url, cardUrl, wideUrl, width, height, focalX, focalY, alt: {de, en}}` (paths on the
shop's origin under `/media`). `archived` content has left the shop: projections hide it (search drops it,
ordering stops selling it) but keep what refers to it.

## `catalog.restaurant.v1`

| `type` | When | `data` |
|---|---|---|
| `nothingeats.catalog.restaurant.published.v1` | A restaurant was created or changed | `restaurantId`, `slug`, `name`, `description` (`{de, en}`), `cuisine`, `rating`, `deliveryMinutes`, `deliveryFeeCents`, `minimumOrderCents`, `latitude`, `longitude`, `opensAt`, `closesAt` (`HH:mm`, Berlin time), `image`, `archived` |

## `catalog.dish.v1`

| `type` | When | `data` |
|---|---|---|
| `nothingeats.catalog.dish.published.v1` | A dish was created or changed | `dishId`, `restaurantId`, `restaurantSlug`, `restaurantName`, `cuisine`, `slug`, `section`, `name`, `description` (each `{de, en}`), `priceCents`, `dietary`, `allergens` (lowercase, sorted), `spiceLevel` (0–3), `calories`, `moods`, `available`, `image`, `archived` |

The dish event carries the restaurant's slug, name and cuisine. When those change, or the restaurant is
archived or comes back, catalog publishes all its dishes again. A dish's `archived` means it has left the
shop, by itself or with its restaurant.

## `catalog.deal.v1`

| `type` | When | `data` |
|---|---|---|
| `nothingeats.catalog.deal.published.v1` | A flash deal was planned (it starts at `startsAt`, usually right away) | `dealId`, `dishId`, `restaurantId`, `percentOff`, `priceCents` (the deal price), `regularPriceCents` (the dish's price when the deal was planned), `startsAt`, `endsAt` (ISO-8601 instants) |

Deals are planned by catalog or scheduled by an editor in the backoffice. A planned deal never changes;
a scheduled one can be rescheduled until it runs, and a cancelled deal is published again with its window
closed (`endsAt` at the cancellation, or equal to `startsAt` when it never began). Consumers apply a deal
while `startsAt <= now < endsAt` and use the lower of the deal price and the dish's current price. There
is no stock: a restaurant always has food. Ended deals stay on the compacted topic as harmless history.
