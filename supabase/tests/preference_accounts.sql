-- Run against a disposable PostgreSQL database. All fixture changes roll back.
begin;
create schema auth;
create role anon nologin;
create role authenticated nologin;
create table auth.users (id uuid primary key);
create function auth.uid() returns uuid language sql stable as
    $$ select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
grant usage on schema auth to authenticated, anon;
grant execute on function auth.uid() to authenticated, anon;
alter default privileges in schema public grant all on tables to anon, authenticated;
insert into auth.users values ('10000000-0000-4000-8000-000000000001'), ('20000000-0000-4000-8000-000000000002');
\ir ../migrations/202610050001_preference_accounts.sql
do $$ begin
    if has_table_privilege('authenticated', 'public.dictai_preference_replicas', 'TRUNCATE')
       or has_table_privilege('authenticated', 'public.dictai_preference_replicas', 'DELETE')
       or has_table_privilege('authenticated', 'public.dictai_preference_replicas', 'TRIGGER') then
        raise exception 'Inherited unsafe privileges retained';
    end if;
end $$;

set local role authenticated;
select set_config('request.jwt.claim.sub', '10000000-0000-4000-8000-000000000001', true);
insert into public.dictai_preference_replicas(user_id, device_id, document) values
    (auth.uid(), '30000000-0000-4000-8000-000000000003', '{"version":1,"entries":{}}');
do $$ begin
    if (select count(*) from public.dictai_preference_replicas) <> 1 then raise exception 'Own replica missing'; end if;
    begin
        insert into public.dictai_preference_replicas(user_id, device_id, document) values
            ('20000000-0000-4000-8000-000000000002', '30000000-0000-4000-8000-000000000003', '{"version":1,"entries":{}}');
        raise exception 'Cross-account insertion accepted';
    exception when insufficient_privilege then null; end;
end $$;

select set_config('request.jwt.claim.sub', '20000000-0000-4000-8000-000000000002', true);
do $$ begin
    if exists (select from public.dictai_preference_replicas) then raise exception 'Cross-account read accepted'; end if;
    update public.dictai_preference_replicas set document = '{"version":1,"entries":{}}'
        where user_id = '10000000-0000-4000-8000-000000000001';
    if found then raise exception 'Cross-account update accepted'; end if;
end $$;
insert into public.dictai_preference_replicas(user_id, device_id, document) values
    (auth.uid(), '40000000-0000-4000-8000-000000000004', '{"version":1,"entries":{}}');
do $$ begin
    begin
        update public.dictai_preference_replicas set user_id = '10000000-0000-4000-8000-000000000001';
        raise exception 'Replica ownership reassignment accepted';
    exception when insufficient_privilege then null; end;
    begin
        update public.dictai_preference_replicas set document = '{"version":2,"entries":{}}';
        raise exception 'Unknown schema accepted';
    exception when check_violation then null; end;
end $$;

reset role;
set local role anon;
do $$ begin
    begin
        perform * from public.dictai_preference_replicas;
        raise exception 'Anonymous read accepted';
    exception when insufficient_privilege then null; end;
end $$;
reset role;
rollback;
\echo Preference account isolation checks passed
