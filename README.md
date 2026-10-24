# nothingeats-catalog-service

Catalog bounded context of [NothingEats](https://github.com/slashncode/nothingeats): restaurants, dishes, menus, translations, flash deals and fake stock.

> Status: freshly created from [`nothingeats-kotlin-service-template`](https://github.com/slashncode/nothingeats-kotlin-service-template). It still contains the template's `Item` example, deployed end to end as the first service; the catalog domain replaces it in phase 1.

## API

Public under `/api/catalog/v1` (routed by the gateway at `nothingeats.localhost`).

## Run

```bash
./gradlew build                     # compile, ktlint, tests (Docker needed for Testcontainers)

export DATABASE__URL=jdbc:postgresql://localhost:15432/catalog DATABASE__USER=catalog DATABASE__PASSWORD=...
./gradlew :app:run --args=migrate
./gradlew :app:run
```

Modules, conventions and the delivery pipeline are described in the template's README.

## License

MIT
