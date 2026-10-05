-- JPEG originals are private and addressed by their bytes, never by a public URL.
insert into storage.buckets(id, name, public, file_size_limit, allowed_mime_types)
values ('dictai-note-images', 'dictai-note-images', false, 8388608, array['image/jpeg']::text[])
on conflict(id) do update set public = false, file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

-- Supabase owns these managed tables; RLS is already enabled and ALTER ownership is restricted.
do $$ begin
    if exists (select 1 from pg_catalog.pg_class c join pg_catalog.pg_namespace n on n.oid = c.relnamespace
        where n.nspname = 'storage' and c.relname in ('objects', 'buckets') and not c.relrowsecurity) then
        raise exception 'Storage RLS must already be enabled';
    end if;
end; $$;
-- RLS cannot protect TRUNCATE. Keep API privileges for other buckets while removing unsafe table privileges.
revoke truncate, references, trigger on storage.objects, storage.buckets from public, anon, authenticated;
grant select, insert on storage.objects to authenticated;

create policy dictai_images_read_own on storage.objects for select to authenticated
    using (bucket_id = 'dictai-note-images' and split_part(name, '/', 1) = (select auth.uid())::text);
create policy dictai_images_insert_own on storage.objects for insert to authenticated
    with check (bucket_id = 'dictai-note-images' and split_part(name, '/', 1) = (select auth.uid())::text);

-- Restrictive guards still apply when an existing project has permissive policies for all buckets.
create policy dictai_images_private_boundary on storage.objects as restrictive for all to public
    using (bucket_id <> 'dictai-note-images' or (
        current_user = 'authenticated' and split_part(name, '/', 1) = (select auth.uid())::text
        and name ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{64}\.jpg$'
    ))
    with check (bucket_id <> 'dictai-note-images' or (
        current_user = 'authenticated' and split_part(name, '/', 1) = (select auth.uid())::text
        and name ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{64}\.jpg$'
    ));
create policy dictai_images_no_overwrite on storage.objects as restrictive for update to public
    using (bucket_id <> 'dictai-note-images') with check (bucket_id <> 'dictai-note-images');
create policy dictai_images_no_delete on storage.objects as restrictive for delete to public
    using (bucket_id <> 'dictai-note-images');

-- Client roles cannot change this bucket to public, rename it, or remove it.
create policy dictai_images_bucket_no_update on storage.buckets as restrictive for update to public
    using (id <> 'dictai-note-images') with check (id <> 'dictai-note-images');
create policy dictai_images_bucket_no_delete on storage.buckets as restrictive for delete to public
    using (id <> 'dictai-note-images');
