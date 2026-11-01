-- Phase 6: the backoffice owns the content (record 040). Catalog keeps its model and adds what the
-- backoffice publishes: images, archiving, scheduled deals, the planner's settings.

alter table restaurants
    add column image    text,
    add column archived boolean not null default false;

alter table dishes
    add column image    text,
    add column archived boolean not null default false;

-- A cancelled deal closes its window; one cancelled before its start has an empty window.
alter table deals drop constraint deals_window;
alter table deals add constraint deals_window check (ends_at >= starts_at);

-- Dishes that arrived before their restaurant; applied when it arrives.
create table pending_dishes
(
    id            uuid primary key,
    restaurant_id uuid        not null,
    input         text        not null,
    received_at   timestamptz not null
);

create index pending_dishes_restaurant on pending_dishes (restaurant_id);

-- The deal planner's settings as the backoffice published them last (one row).
create table deal_planner_settings
(
    id                  integer primary key default 1 constraint deal_planner_settings_single check (id = 1),
    enabled             boolean   not null,
    concurrent          integer   not null,
    durations_minutes   integer[] not null,
    percentages         integer[] not null,
    minimum_price_cents bigint    not null
);
