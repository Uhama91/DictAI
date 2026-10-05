-- DictAI is usable locally; these rows exist only after optional account connection.
create table public.dictai_preference_replicas (
    user_id uuid not null references auth.users(id) on delete cascade,
    device_id uuid not null,
    document jsonb not null,
    updated_at timestamptz not null default now(),
    primary key (user_id, device_id),
    constraint preference_document_v1 check (
        jsonb_typeof(document) = 'object'
        and document ? 'version' and document ? 'entries'
        and document -> 'version' = '1'::jsonb
        and jsonb_typeof(document -> 'entries') = 'object'
        -- JSONB inserts spaces and normalizes escapes; allow headroom over the 8 MiB wire limit.
        and octet_length(document::text) <= 16777216
    )
);

alter table public.dictai_preference_replicas enable row level security;
alter table public.dictai_preference_replicas force row level security;
revoke all on public.dictai_preference_replicas from public, anon, authenticated;
grant select, insert, update on public.dictai_preference_replicas to authenticated;

create policy preferences_read_own on public.dictai_preference_replicas
    for select to authenticated using ((select auth.uid()) = user_id);
create policy preferences_insert_own on public.dictai_preference_replicas
    for insert to authenticated with check ((select auth.uid()) = user_id);
create policy preferences_update_own on public.dictai_preference_replicas
    for update to authenticated using ((select auth.uid()) = user_id)
    with check ((select auth.uid()) = user_id);

-- Bound per-account storage and ensure a writer cannot forge server timestamps.
create function public.dictai_guard_preference_replica() returns trigger
language plpgsql set search_path = '' as $$
begin
    if new.user_id is distinct from auth.uid() then
        raise exception 'Preference account mismatch' using errcode = '42501';
    end if;
    if tg_op = 'UPDATE' and (new.user_id <> old.user_id or new.device_id <> old.device_id) then
        raise exception 'Preference replica identity cannot change' using errcode = '42501';
    end if;
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(new.user_id::text, 0));
    if tg_op = 'INSERT'
       and not exists (select 1 from public.dictai_preference_replicas where user_id = new.user_id and device_id = new.device_id)
       and (select count(*) from public.dictai_preference_replicas where user_id = new.user_id) >= 100 then
        raise exception 'Preference device limit reached' using errcode = '23514';
    end if;
    new.updated_at := pg_catalog.now();
    return new;
end;
$$;
revoke all on function public.dictai_guard_preference_replica() from public;
create trigger dictai_guard_preference_replica before insert or update
    on public.dictai_preference_replicas for each row
    execute function public.dictai_guard_preference_replica();
