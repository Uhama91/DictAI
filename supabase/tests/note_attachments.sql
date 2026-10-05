-- Run only against a disposable PostgreSQL database; all fixture changes roll back.
begin;
create schema auth;
create schema storage;
create role anon nologin;
create role authenticated nologin;
create function auth.uid() returns uuid language sql stable as
    $$ select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
grant usage on schema auth, storage to anon, authenticated;
grant execute on function auth.uid() to anon, authenticated;
create table storage.buckets (
    id text primary key, name text not null, public boolean not null default false,
    file_size_limit bigint, allowed_mime_types text[]
);
create table storage.objects (
    id uuid primary key default gen_random_uuid(), bucket_id text not null references storage.buckets(id),
    name text not null, metadata jsonb, unique(bucket_id, name)
);
-- Existing projects may have broad grants and policies; the new bucket must still be isolated.
grant all on storage.buckets, storage.objects to anon, authenticated;
alter table storage.buckets enable row level security;
alter table storage.objects enable row level security;
create policy legacy_bucket_access on storage.buckets for all to public using (true) with check (true);
create policy legacy_object_access on storage.objects for all to public using (true) with check (true);
insert into storage.buckets(id, name, public) values ('other-app', 'other-app', true);
\ir ../migrations/202610050002_note_attachments.sql

do $$ begin
    if not exists (select from storage.buckets where id = 'dictai-note-images' and not public
        and file_size_limit = 8388608 and allowed_mime_types = array['image/jpeg']::text[]) then
        raise exception 'Private JPEG bucket missing or incorrectly bounded';
    end if;
    if has_table_privilege('authenticated', 'storage.objects', 'TRUNCATE,TRIGGER,REFERENCES')
        or has_table_privilege('anon', 'storage.objects', 'TRUNCATE,TRIGGER,REFERENCES') then
        raise exception 'Unsafe storage table privileges inherited';
    end if;
end $$;

set local role authenticated;
select set_config('request.jwt.claim.sub', '10000000-0000-4000-8000-000000000001', true);
insert into storage.objects(bucket_id, name) values ('dictai-note-images',
    '10000000-0000-4000-8000-000000000001/' || repeat('a', 64) || '.jpg');
do $$ begin
    if (select count(*) from storage.objects where bucket_id = 'dictai-note-images') <> 1 then
        raise exception 'Own image unavailable';
    end if;
    begin
        insert into storage.objects(bucket_id, name) values ('dictai-note-images',
            '20000000-0000-4000-8000-000000000002/' || repeat('b',64) || '.jpg');
        raise exception 'Cross-account upload accepted';
    exception when insufficient_privilege then null; end;
    begin
        insert into storage.objects(bucket_id, name) values ('dictai-note-images',
            '10000000-0000-4000-8000-000000000001/../outside.jpg');
        raise exception 'Malformed object path accepted';
    exception when insufficient_privilege then null; end;
    update storage.objects set metadata = '{"changed":true}' where bucket_id = 'dictai-note-images';
    if found then raise exception 'Immutable object update accepted'; end if;
    delete from storage.objects where bucket_id = 'dictai-note-images';
    if found then raise exception 'Immutable object deletion accepted'; end if;
    update storage.buckets set public = true where id = 'dictai-note-images';
    if found then raise exception 'Private bucket made public'; end if;
    delete from storage.buckets where id = 'dictai-note-images';
    if found then raise exception 'Private bucket deleted'; end if;
    begin
        insert into storage.objects(bucket_id, name) values ('dictai-note-images',
            '10000000-0000-4000-8000-000000000001/' || repeat('a',64) || '.jpg')
            on conflict(bucket_id, name) do update set metadata = '{"overwritten":true}';
        raise exception 'Object upsert accepted';
    exception when insufficient_privilege then null; end;
    begin
        truncate storage.objects;
        raise exception 'Storage truncate accepted';
    exception when insufficient_privilege then null; end;
end $$;

select set_config('request.jwt.claim.sub', '20000000-0000-4000-8000-000000000002', true);
do $$ begin
    if exists (select from storage.objects where bucket_id = 'dictai-note-images') then
        raise exception 'Cross-account image read accepted';
    end if;
end $$;

-- The migration leaves legitimate operations for another application's bucket unchanged.
insert into storage.objects(bucket_id, name) values ('other-app', 'legitimate-file.txt');
update storage.objects set name = 'renamed.txt' where bucket_id = 'other-app';
delete from storage.objects where bucket_id = 'other-app';

reset role;
set local role anon;
-- Keep a forged sub claim in this fixture to prove the role is checked, too.
select set_config('request.jwt.claim.sub', '10000000-0000-4000-8000-000000000001', true);
do $$ begin
    if exists (select from storage.objects where bucket_id = 'dictai-note-images') then
        raise exception 'Anonymous image read accepted';
    end if;
    begin
        insert into storage.objects(bucket_id, name) values ('dictai-note-images',
            '10000000-0000-4000-8000-000000000001/' || repeat('c',64) || '.jpg');
        raise exception 'Anonymous upload accepted';
    exception when insufficient_privilege then null; end;
end $$;
reset role;
rollback;
\echo Private immutable image isolation checks passed
