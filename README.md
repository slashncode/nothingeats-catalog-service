# nothingeats-catalog-service

Catalog bounded context of [NothingEats](https://github.com/slashncode/nothingeats): restaurants, dishes and menus in German and English. Flash deals and fake stock follow in a later phase.

## API (`/api/catalog/v1`)

| Method | Path | Auth | What |
|---|---|---|---|
| `GET` | `/restaurants?cursor=&limit=&cuisine=` | – | Restaurants ordered by slug, cursor-paginated (`limit` 1–50, default 20), optionally one cuisine |
| `GET` | `/restaurants/{slug}` | – | The restaurant with its menu, dishes grouped by section |
| `GET` | `/dishes/{id}` | – | One dish with its restaurant |
| `PUT` | `/admin/restaurants/{slug}` | role `admin` | Create or update a restaurant (both languages in one body) |
| `PUT` | `/admin/restaurants/{slug}/dishes/{dish}` | role `admin` | Create or update a dish |

- **Language**: `?lang=de|en` wins over `Accept-Language`; anything else is German. Responses carry `Content-Language` and `Vary: Accept-Language`.
- **Open now**: computed per request in Berlin time; opening hours may run past midnight (`18:00`–`02:00`).
- **Money** is always in euro cents. Allergens are the 14 EU declarable allergens, dietary tags are `vegan`, `vegetarian`, `gluten_free`, `lactose_free`, `halal`.
- Errors are RFC 9457 problems with stable codes: `RESTAURANT_NOT_FOUND`, `DISH_NOT_FOUND`, `CURSOR_INVALID`, `CATALOG_INVALID` (with the offending field).

Every change that actually changes something is published through the transactional outbox, with both languages in the payload: see [docs/events.md](docs/events.md). Upserts that change nothing publish nothing.

## Demo data

`adapters/src/main/resources/seed/catalog.json` holds eight restaurants in Hamburg with six dishes each. `cron load-seed` imports it and is safe to run again; in the cluster it runs as a CronJob and can be triggered by hand:

```bash
kubectl -n nothingeats create job --from=cronjob/catalog-service-load-seed seed-$(date +%s)
```

## Run

```bash
./gradlew build                     # compile, ktlint, tests (Docker needed for Testcontainers)

export DATABASE__URL=jdbc:postgresql://localhost:15432/catalog DATABASE__USER=catalog DATABASE__PASSWORD=...
./gradlew :app:run --args=migrate
./gradlew :app:run --args="cron load-seed"
./gradlew :app:run

# in the k3d cluster, from the hub repo (Tilt, live rebuilds; stop with just dev-stop catalog-service)
just dev catalog-service
```

Admin routes verify tokens against the identity service's JWKS (`JWT__JWKS_URL`).

## License

MIT
