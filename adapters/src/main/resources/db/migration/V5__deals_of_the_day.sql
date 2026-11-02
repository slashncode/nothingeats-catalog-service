-- Deals of the day: the planner's deals run until midnight, so its fixed durations go. Deals know
-- who made them: only the planner's can be ended through catalog's admin API, the backoffice
-- cancels its own. Rows from before cannot tell; they count as the planner's.
alter table deals add column source text not null default 'PLANNER';
alter table deals add constraint deals_source check (source in ('PLANNER', 'BACKOFFICE'));
alter table deal_planner_settings drop column durations_minutes;
