-- Flash deals: a dish costs less for a while. No stock column: a restaurant always has food.
create table deals
(
    id                  uuid primary key,
    dish_id             uuid        not null references dishes (id) on delete cascade,
    restaurant_id       uuid        not null references restaurants (id) on delete cascade,
    percent_off         integer     not null,
    price_cents         bigint      not null,
    regular_price_cents bigint      not null,
    starts_at           timestamptz not null,
    ends_at             timestamptz not null,
    constraint deals_window check (ends_at > starts_at)
);

create index deals_ends_at on deals (ends_at);
