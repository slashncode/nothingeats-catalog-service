-- The template's placeholder table goes; the catalog replaces it.
drop table items;

create table restaurants
(
    id                  uuid primary key,
    slug                text             not null constraint restaurants_slug_key unique,
    name                text             not null,
    description_de      text             not null,
    description_en      text             not null,
    cuisine             text             not null,
    rating              double precision not null,
    delivery_minutes    integer          not null,
    delivery_fee_cents  bigint           not null,
    minimum_order_cents bigint           not null,
    latitude            double precision not null,
    longitude           double precision not null,
    opens_at            time             not null,
    closes_at           time             not null
);

create index restaurants_cuisine on restaurants (cuisine);

create table dishes
(
    id             uuid primary key,
    restaurant_id  uuid    not null references restaurants (id) on delete cascade,
    slug           text    not null,
    section_de     text    not null,
    section_en     text    not null,
    name_de        text    not null,
    name_en        text    not null,
    description_de text    not null,
    description_en text    not null,
    price_cents    bigint  not null,
    dietary        text[]  not null,
    allergens      text[]  not null,
    spice_level    integer not null,
    calories       integer not null,
    moods          text[]  not null,
    position       integer not null,
    available      boolean not null,
    constraint dishes_restaurant_slug_key unique (restaurant_id, slug)
);
