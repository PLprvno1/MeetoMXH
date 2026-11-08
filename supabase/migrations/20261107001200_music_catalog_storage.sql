-- Task 02: fixed Meeto music catalog, private Storage bucket and metadata v2.
-- Forward-only. Catalog rows start inactive; owner activates them only after
-- the upload helper verifies every exact object and checksum.
-- This bucket is independent from public.media_assets and media cleanup.
BEGIN;

DO $$ DECLARE migration_present boolean;
BEGIN
 IF current_setting('server_version_num')::integer < 120000 THEN
  RAISE EXCEPTION 'Music catalog requires PostgreSQL 12+';
 END IF;
 IF to_regclass('supabase_migrations.schema_migrations') IS NOT NULL THEN
  EXECUTE 'SELECT EXISTS(SELECT 1 FROM supabase_migrations.schema_migrations WHERE version=$1)'
   INTO migration_present USING '20261107001200';
  IF migration_present THEN RAISE EXCEPTION 'Music catalog migration already appears in the ledger'; END IF;
 END IF;
 IF to_regprocedure('private.valid_music_metadata(jsonb)') IS NULL
 OR to_regprocedure('private.bind_post_music(uuid,jsonb)') IS NULL
 OR to_regprocedure('private.bind_story_music(uuid,jsonb)') IS NULL
 OR to_regprocedure('private.get_music_publish_capabilities()') IS NULL
 OR to_regprocedure('public.get_music_publish_capabilities()') IS NULL
 OR to_regprocedure('private.is_active()') IS NULL
 OR to_regprocedure('storage.allow_any_operation(text[])') IS NULL
 OR to_regclass('public.posts') IS NULL OR to_regclass('public.stories') IS NULL
 OR to_regclass('storage.objects') IS NULL OR to_regclass('storage.buckets') IS NULL THEN
  RAISE EXCEPTION 'Apply the canonical Openverse music and private Storage prerequisites first';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='posts' AND column_name='music_metadata')
 OR NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='posts' AND column_name='music_metadata_hash')
 OR NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stories' AND column_name='music_metadata')
 OR NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='stories' AND column_name='music_metadata_hash') THEN
  RAISE EXCEPTION 'Openverse music metadata columns are missing';
 END IF;
 IF EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
  WHERE n.nspname='private' AND p.proname IN ('valid_music_metadata','bind_post_music','bind_story_music')
   AND pg_get_userbyid(p.proowner)<>current_user) THEN
  RAISE EXCEPTION 'Music metadata function owner differs from migration role; review ownership before deployment';
 END IF;
 IF to_regclass('public.music_catalog_tracks') IS NOT NULL
 OR to_regprocedure('private.valid_music_library_metadata(jsonb,boolean)') IS NOT NULL
 OR to_regprocedure('private.get_music_catalog_v2(text,text,integer,integer)') IS NOT NULL
 OR to_regprocedure('public.get_music_catalog(text,text,integer,integer)') IS NOT NULL
 OR to_regprocedure('public.get_music_library_capabilities()') IS NOT NULL
 OR EXISTS(SELECT 1 FROM storage.buckets WHERE id='music-library')
 OR EXISTS(SELECT 1 FROM pg_policies WHERE schemaname='storage' AND tablename='objects'
  AND policyname IN ('meeto_music_library_read_guard','meeto_music_library_anon_deny',
   'meeto_music_library_read','meeto_music_library_no_insert','meeto_music_library_no_update','meeto_music_library_no_delete')) THEN
  RAISE EXCEPTION 'Music catalog objects already exist; inspect actual Cloud objects before any retry';
 END IF;
 IF EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
  WHERE (n.nspname='public' AND p.proname IN ('get_music_catalog','get_music_library_capabilities'))
     OR (n.nspname='private' AND p.proname IN
       ('valid_music_library_metadata','get_music_catalog_v2','get_music_library_capabilities',
        'meeto_music_library_object_readable'))) THEN
  RAISE EXCEPTION 'Unexpected Task 02 function overloads exist; inspect the actual Cloud catalog';
 END IF;
 IF NOT (SELECT relrowsecurity FROM pg_class WHERE oid='storage.objects'::regclass) THEN
  RAISE EXCEPTION 'Storage object RLS is not enabled';
 END IF;
 IF NOT has_schema_privilege(current_user,'public','CREATE')
  OR NOT has_schema_privilege('authenticated','public','USAGE')
  OR NOT has_schema_privilege(current_user,'private','USAGE')
  OR NOT has_table_privilege(current_user,'storage.buckets','INSERT') THEN
  RAISE EXCEPTION 'Migration role requires schema privileges and permission to insert bucket metadata';
 END IF;
 -- Managed Storage may authorize policy DDL through supautils.policy_grants
 -- without table ownership. Let CREATE POLICY check the effective permission;
 -- an authorization error aborts this transaction before COMMIT.
END $$;

CREATE TABLE public.music_catalog_tracks (
 track_id text NOT NULL CHECK(track_id ~ '^meeto_music_[0-9]{3}$'),
 catalog_version text NOT NULL CHECK(char_length(catalog_version) BETWEEN 1 AND 80),
 sort_order integer NOT NULL CHECK(sort_order BETWEEN 1 AND 10000),
 title text NOT NULL CHECK(char_length(btrim(title)) BETWEEN 1 AND 300),
 artist text NOT NULL CHECK(char_length(btrim(artist)) BETWEEN 1 AND 300),
 duration_ms integer NOT NULL CHECK(duration_ms BETWEEN 1000 AND 3600000),
 bucket_id text NOT NULL DEFAULT 'music-library' CHECK(bucket_id='music-library'),
 object_path text NOT NULL CHECK(object_path ~ '^v[0-9]+/meeto_music_[0-9]{3}[.]ogg$'),
 mime_type text NOT NULL DEFAULT 'audio/ogg' CHECK(mime_type='audio/ogg'),
 codec text NOT NULL DEFAULT 'vorbis' CHECK(codec='vorbis'),
 container text NOT NULL DEFAULT 'ogg' CHECK(container='ogg'),
 file_size_bytes bigint NOT NULL CHECK(file_size_bytes BETWEEN 1 AND 5242880),
 sha256 text NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
 license_id text NOT NULL CHECK(license_id='CC0-1.0'),
 license_url text NOT NULL CHECK(license_url='https://creativecommons.org/publicdomain/zero/1.0/'),
 source_url text NOT NULL CHECK(source_url ~ '^https://[^/@[:space:]]+(/|[?#]|$)'),
 attribution text NOT NULL CHECK(char_length(btrim(attribution)) BETWEEN 1 AND 800),
 is_active boolean NOT NULL DEFAULT false,
 PRIMARY KEY(track_id,catalog_version),
 UNIQUE(catalog_version,sort_order),
 UNIQUE(bucket_id,object_path)
);
ALTER TABLE public.music_catalog_tracks ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.music_catalog_tracks FROM PUBLIC,anon,authenticated,service_role;

-- Exact rows generated from MeetoMusicSource/MeetoMusicReady/music_catalog.json.
-- Keep inactive until the upload script verifies all Storage objects.
INSERT INTO public.music_catalog_tracks
 (track_id,catalog_version,sort_order,title,artist,duration_ms,object_path,file_size_bytes,sha256,
  license_id,license_url,source_url,attribution,is_active)
VALUES
 ('meeto_music_001','meeto_bundled_cc0_v1',1,'Ludum Dare 28 01','Abstraction',118445,'v1/meeto_music_001.ogg',1350563,'7a48b4ab1b0dafbc139e1667d2c91e7ea8521841d12e620c5f0f3b5194013350','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_002','meeto_bundled_cc0_v1',2,'Ludum Dare 28 02','Abstraction',144055,'v1/meeto_music_002.ogg',1548465,'e2b0ec975a0673ad3ee38206d510dbcf0eea5a5503c9d266579b2e09c7a05611','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_003','meeto_bundled_cc0_v1',3,'Ludum Dare 28 03','Abstraction',132430,'v1/meeto_music_003.ogg',1340590,'3080fb7caa842a795f15b97d1d507ae23948dda07194495e930c005f0d364760','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_004','meeto_bundled_cc0_v1',4,'Ludum Dare 28 04','Abstraction',128012,'v1/meeto_music_004.ogg',1315119,'d9d62718f68c0f6ca36e243f58bb183fcaa9f833454d31fe491b8f74d5250d53','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_005','meeto_bundled_cc0_v1',5,'Ludum Dare 28 05','Abstraction',126901,'v1/meeto_music_005.ogg',1401152,'b67afaf65ee31f509e55faccc26b3ef097786dbbf1447116b8a3e2d7f4a6c18c','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_006','meeto_bundled_cc0_v1',6,'Ludum Dare 28 06','Abstraction',149923,'v1/meeto_music_006.ogg',1777659,'3bfac1b347adaa120cf19dd551736ae65da15c9f2ad63d02c22f7016bf857c6b','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_007','meeto_bundled_cc0_v1',7,'Ludum Dare 28 07','Abstraction',107664,'v1/meeto_music_007.ogg',1164283,'5c5056a6a8799c3cd7ee8d4cf1d37a1f0ab149f3e5dead0dcd877bf38b07861c','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_008','meeto_bundled_cc0_v1',8,'Ludum Dare 28 08','Abstraction',137143,'v1/meeto_music_008.ogg',1633920,'900c296937c6fabb1159f28517196ba7acd8f5876f47cf361ad4ac186a8dd914','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_009','meeto_bundled_cc0_v1',9,'Ludum Dare 30 01','Abstraction',99740,'v1/meeto_music_009.ogg',1049570,'f44c385c01a48fc61d712f03f40e0ee31ebbf783647867422adbf8fb62e04607','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_010','meeto_bundled_cc0_v1',10,'Ludum Dare 30 02','Abstraction',113143,'v1/meeto_music_010.ogg',1193297,'2821562e90ec124d0afe6c7f39b9d89a049e4650bd941c728a8bc016931115e5','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_011','meeto_bundled_cc0_v1',11,'Ludum Dare 30 03','Abstraction',86400,'v1/meeto_music_011.ogg',995774,'25f909a78b7d8e8b1f596821c90bf4290de0552cb740c8204c8bcd5c2b7dc34a','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_012','meeto_bundled_cc0_v1',12,'Ludum Dare 30 04','Abstraction',59294,'v1/meeto_music_012.ogg',646077,'a9f5fbbd783c6d5697d46296cf210fc8a9edf131fd27af8df02657a43c81e028','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_013','meeto_bundled_cc0_v1',13,'Ludum Dare 30 05','Abstraction',154500,'v1/meeto_music_013.ogg',1478333,'afc1443ef0dff8defd492bea564bc4d8ffc513a90d9fdb07111ff766ca46b13a','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_014','meeto_bundled_cc0_v1',14,'Ludum Dare 30 06','Abstraction',121263,'v1/meeto_music_014.ogg',1241613,'300a6e5b1d45e268403b52bf91c733cc9fb20b8c37af0afa7361809874b57dd8','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_015','meeto_bundled_cc0_v1',15,'Ludum Dare 30 07','Abstraction',65143,'v1/meeto_music_015.ogg',667576,'8ba2046ae8a194f77cfe508de04ba2b9b62b48dbb1e2d1b09d26c688214bfbcb','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_016','meeto_bundled_cc0_v1',16,'Ludum Dare 30 08','Abstraction',83077,'v1/meeto_music_016.ogg',926978,'da935b6f0c6d2c9ada0968415643c6292cd7f3eca64d5af9c2f2f162f16f938e','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_017','meeto_bundled_cc0_v1',17,'Ludum Dare 30 09','Abstraction',55579,'v1/meeto_music_017.ogg',623813,'c4cc2aa9b6aafb730d519153803bacc5c97da9cbb09d6b7390a10af69b128896','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_018','meeto_bundled_cc0_v1',18,'Ludum Dare 32 01','Abstraction',47998,'v1/meeto_music_018.ogg',468443,'9c5f4980c202d2d52739fc6eda6da321f8c6b7950c2c6f305aca9eae577ecd67','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_019','meeto_bundled_cc0_v1',19,'Ludum Dare 32 02','Abstraction',78000,'v1/meeto_music_019.ogg',814449,'ead952253a5e86e2484e424325123c6c45d982090b7b35039268a9db34fe2ffc','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_020','meeto_bundled_cc0_v1',20,'Ludum Dare 32 03','Abstraction',63993,'v1/meeto_music_020.ogg',618348,'069276d12c0d6c34d051fb16483e9dbaefbe390e618214ff04378543ea792760','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_021','meeto_bundled_cc0_v1',21,'Ludum Dare 32 05','Abstraction',63717,'v1/meeto_music_021.ogg',685202,'566c47240a6c2e4594418ba8608c84ebf7237795814f138efa386d6d32252e61','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_022','meeto_bundled_cc0_v1',22,'Ludum Dare 38 01','Abstraction',67200,'v1/meeto_music_022.ogg',763685,'6e6714d1ca8676f5cc40d75d80f6e3abcc647367227cf396b5054796c6bce577','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_023','meeto_bundled_cc0_v1',23,'Ludum Dare 38 02','Abstraction',64000,'v1/meeto_music_023.ogg',703948,'8732fff45d95b51399fd6416f286065562de55985bf04ed6964c70127afaebfc','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_024','meeto_bundled_cc0_v1',24,'Ludum Dare 38 03','Abstraction',62118,'v1/meeto_music_024.ogg',661362,'fdab0a5f433f7b8723fb973dd181ceedc02e1fc97511259c790e2aaf0de29867','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_025','meeto_bundled_cc0_v1',25,'Ludum Dare 38 04','Abstraction',41143,'v1/meeto_music_025.ogg',460750,'6f2cb3f152ced08df160ac64db4534bc7844122bf8c2cab7aa7ad0bce47568e2','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_026','meeto_bundled_cc0_v1',26,'Ludum Dare 38 05','Abstraction',55991,'v1/meeto_music_026.ogg',639194,'546f49c746db8ee365a20874b1317842beb56b073c2175e1dff403519ec8fa5c','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_027','meeto_bundled_cc0_v1',27,'Ludum Dare 38 06','Abstraction',60000,'v1/meeto_music_027.ogg',754290,'538acbfa708933d212d230878a1444c61b5171eeabcee84e0a25e0e82ddb8f34','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_028','meeto_bundled_cc0_v1',28,'Ludum Dare 38 07','Abstraction',60000,'v1/meeto_music_028.ogg',729832,'cef51f93c0eb79e9c940d67810dd5976594f02668c889a2293b1981ac07f2177','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_029','meeto_bundled_cc0_v1',29,'Ludum Dare 38 08','Abstraction',68576,'v1/meeto_music_029.ogg',729622,'a135ac1e2967eea225fc166bdb496e897f0aeed5214676bfedba4df14a84478b','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_030','meeto_bundled_cc0_v1',30,'Ludum Dare 38 10','Abstraction',64225,'v1/meeto_music_030.ogg',767213,'b2a6f79a1e0d77cf68f5174a49312b3cb375337f891498885b97fd554bf66c4f','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_031','meeto_bundled_cc0_v1',31,'Patreon Challenge 01','Abstraction',58445,'v1/meeto_music_031.ogg',684792,'38df1dbeb344b6d5b5632eff2d663ec06b2e404899db91003a58f3076d7fc21c','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_032','meeto_bundled_cc0_v1',32,'Patreon Challenge 02','Abstraction',64000,'v1/meeto_music_032.ogg',718369,'4256423ffe2e82622686ce342b7eedf7a0c7670005915535732908c9cd809f02','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_033','meeto_bundled_cc0_v1',33,'Patreon Challenge 03','Abstraction',60000,'v1/meeto_music_033.ogg',704805,'b331bd57dbabd0bcfec9dd9f3c52b80a3240d3454c5e9e5ff7190d5bca0a98de','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_034','meeto_bundled_cc0_v1',34,'Patreon Challenge 04','Abstraction',103662,'v1/meeto_music_034.ogg',1000021,'72d7e64f20b2227bdaf6cff69ca71c63304be0a9390f54557da304d3832518e4','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_035','meeto_bundled_cc0_v1',35,'Patreon Challenge 05','Abstraction',64000,'v1/meeto_music_035.ogg',635266,'c903fb40e7cd952e8ac8b5faa00dc860f45ff31f2409f0f02da576ebee418212','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_036','meeto_bundled_cc0_v1',36,'Patreon Challenge 06','Abstraction',48000,'v1/meeto_music_036.ogg',591736,'b742e8c797b648c0246dc37f71e383c71f13bedbef23a339d345bcd218187f41','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_037','meeto_bundled_cc0_v1',37,'Patreon Challenge 07','Abstraction',76800,'v1/meeto_music_037.ogg',826795,'f3c257943345a37c7a7047e8f7c6ff4f8d13758fa4b678037983ff5bb62d5083','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_038','meeto_bundled_cc0_v1',38,'Patreon Challenge 08','Abstraction',62439,'v1/meeto_music_038.ogg',765611,'1da908fdac72b37700eef2231e50f1bad452e73dd2c7b99e1167a9a87586674c','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_039','meeto_bundled_cc0_v1',39,'Patreon Challenge 09','Abstraction',108100,'v1/meeto_music_039.ogg',1120622,'342294ac3b337b4af6d6fa443f1f51fc6c8ddc4d75506bfaa6994edc78f9a443','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_040','meeto_bundled_cc0_v1',40,'Patreon Challenge 11','Abstraction',66667,'v1/meeto_music_040.ogg',734054,'8610bcf28aaeceeae4fbb9e66493670f3eb2c393f135f4ccf3e8f25fb35b9981','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_041','meeto_bundled_cc0_v1',41,'Patreon Challenge 12','Abstraction',36000,'v1/meeto_music_041.ogg',402824,'79e9a1391731c1625037a30a1881a2d54e26d9e79935523d1d4f65a55f41cc38','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_042','meeto_bundled_cc0_v1',42,'Patreon Challenge 13','Abstraction',42659,'v1/meeto_music_042.ogg',484701,'2a769be00c76785bf7b8159085c869afd9c2a45a821f3a06cbd51fb0ff268cb9','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_043','meeto_bundled_cc0_v1',43,'Patreon Challenge 14','Abstraction',62400,'v1/meeto_music_043.ogg',657348,'117ab850b6a279af05b961644081e94085d2a249f532ffb9634e458bafb6843b','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_044','meeto_bundled_cc0_v1',44,'The Verdant Grove','Abstraction',119231,'v1/meeto_music_044.ogg',1186649,'68d5b90b8f6467e1deea7dc1f22dfbb971399627faaba6e8bfde920bdae3ec93','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_045','meeto_bundled_cc0_v1',45,'The Vile Grove','Abstraction',119231,'v1/meeto_music_045.ogg',1212439,'7ab3aa709a239368ddddad1e2ec33903ee9d5a17ff2471ed7e54d601dc80d795','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_046','meeto_bundled_cc0_v1',46,'VGMA Challenge 01','Abstraction',39000,'v1/meeto_music_046.ogg',398005,'ac37c9d6ad4c5721e6c56f8f8eede9fc77374415a6ea100d51a273223eb67bcb','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_047','meeto_bundled_cc0_v1',47,'VGMA Challenge 19','Abstraction',113133,'v1/meeto_music_047.ogg',990760,'ac3a173dc7bf4e95000c017a42ba34760ed1b69f0281786f2de2ddac549dd06c','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_048','meeto_bundled_cc0_v1',48,'VGMA Challenge 20','Abstraction',41143,'v1/meeto_music_048.ogg',459144,'4b69ca968c11b92c507f047e5124e339d6796aa42dab79d2945722776a2210ce','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_049','meeto_bundled_cc0_v1',49,'VGMA Challenge 22','Abstraction',40000,'v1/meeto_music_049.ogg',458010,'4d94e0452078c77e93a522f81d19c87f4f68a2cb2995514b4697c96b818f693f','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false),
 ('meeto_music_050','meeto_bundled_cc0_v1',50,'VGMA Challenge 28','Abstraction',37333,'v1/meeto_music_050.ogg',444696,'90fedc2da6940a9a2ed2f693207635c18bd4f7777c82b436bb4f0177f16477a9','CC0-1.0','https://creativecommons.org/publicdomain/zero/1.0/','https://tallbeard.itch.io/music-loop-bundle','Abstraction',false)
;

CREATE FUNCTION private.valid_music_library_metadata(p jsonb,p_require_active boolean DEFAULT false) RETURNS boolean
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path='' AS $$
DECLARE t public.music_catalog_tracks; v_duration bigint; v_start bigint; v_used bigint; v_music_volume integer; v_source_volume integer;
BEGIN
 IF p IS NULL OR jsonb_typeof(p) IS DISTINCT FROM 'object' OR octet_length(p::text)>8192
  OR (SELECT count(*) FROM jsonb_object_keys(p))<>16
  OR EXISTS(SELECT 1 FROM jsonb_object_keys(p) AS k(name) WHERE name NOT IN
   ('contract_version','source','track_id','catalog_version','title','artist','duration_ms','start_offset_ms',
    'used_duration_ms','music_volume','source_volume','license_id','license_url','source_url','attribution','sha256')) THEN
  RETURN false;
 END IF;
 IF jsonb_typeof(p->'contract_version') IS DISTINCT FROM 'number' OR p->>'contract_version'<>'2'
  OR jsonb_typeof(p->'source') IS DISTINCT FROM 'string' OR p->>'source'<>'meeto_library'
  OR jsonb_typeof(p->'track_id') IS DISTINCT FROM 'string'
  OR coalesce(p->>'track_id' !~ '^meeto_music_[0-9]{3}$',true)
  OR jsonb_typeof(p->'catalog_version') IS DISTINCT FROM 'string'
  OR coalesce(char_length(p->>'catalog_version'),0) NOT BETWEEN 1 AND 80
  OR jsonb_typeof(p->'title') IS DISTINCT FROM 'string' OR coalesce(char_length(btrim(p->>'title')),0) NOT BETWEEN 1 AND 300
  OR jsonb_typeof(p->'artist') IS DISTINCT FROM 'string' OR coalesce(char_length(btrim(p->>'artist')),0) NOT BETWEEN 1 AND 300
  OR jsonb_typeof(p->'license_id') IS DISTINCT FROM 'string' OR p->>'license_id'<>'CC0-1.0'
  OR jsonb_typeof(p->'license_url') IS DISTINCT FROM 'string'
  OR jsonb_typeof(p->'source_url') IS DISTINCT FROM 'string'
  OR jsonb_typeof(p->'attribution') IS DISTINCT FROM 'string'
  OR jsonb_typeof(p->'sha256') IS DISTINCT FROM 'string'
  OR coalesce(p->>'sha256' !~ '^[0-9a-f]{64}$',true) THEN RETURN false; END IF;
 IF jsonb_typeof(p->'duration_ms') IS DISTINCT FROM 'number'
  OR jsonb_typeof(p->'start_offset_ms') IS DISTINCT FROM 'number'
  OR jsonb_typeof(p->'used_duration_ms') IS DISTINCT FROM 'number'
  OR jsonb_typeof(p->'music_volume') IS DISTINCT FROM 'number'
  OR jsonb_typeof(p->'source_volume') IS DISTINCT FROM 'number'
  OR coalesce(p->>'duration_ms','') !~ '^[0-9]{1,8}$'
  OR coalesce(p->>'start_offset_ms','') !~ '^[0-9]{1,8}$'
  OR coalesce(p->>'used_duration_ms','') !~ '^[0-9]{1,5}$'
  OR coalesce(p->>'music_volume','') !~ '^[0-9]{1,3}$'
  OR coalesce(p->>'source_volume','') !~ '^[0-9]{1,3}$' THEN RETURN false; END IF;
 v_duration:=(p->>'duration_ms')::bigint; v_start:=(p->>'start_offset_ms')::bigint;
 v_used:=(p->>'used_duration_ms')::bigint; v_music_volume:=(p->>'music_volume')::integer;
 v_source_volume:=(p->>'source_volume')::integer;
 -- V2 library tracks loop implicitly: start inside the track, then wrap to 0
 -- at EOF until used_duration_ms is reached. No extra metadata key is needed.
 IF v_duration NOT BETWEEN 1000 AND 3600000 OR v_start<0 OR v_used NOT BETWEEN 1000 AND 60000
  OR v_start>=v_duration OR v_music_volume NOT BETWEEN 0 AND 100 OR v_source_volume NOT BETWEEN 0 AND 100 THEN
  RETURN false;
 END IF;
 SELECT * INTO t FROM public.music_catalog_tracks
  WHERE track_id=p->>'track_id' AND catalog_version=p->>'catalog_version';
 IF NOT FOUND OR (p_require_active AND NOT t.is_active) THEN RETURN false; END IF;
 RETURN p->>'title'=t.title AND p->>'artist'=t.artist AND v_duration=t.duration_ms
  AND p->>'license_url'=t.license_url AND p->>'source_url'=t.source_url
  AND p->>'attribution'=t.attribution AND p->>'sha256'=t.sha256;
EXCEPTION WHEN OTHERS THEN RETURN false;
END $$;

-- Preserve the original strict 13-field Openverse contract and add an exact,
-- catalog-backed v2 alternative. Catalog rows remain present when deactivated.
CREATE OR REPLACE FUNCTION private.valid_music_metadata(p jsonb) RETURNS boolean
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path='' AS $$
DECLARE v_duration bigint; v_start bigint; v_used bigint;
BEGIN
 IF p IS NULL OR jsonb_typeof(p) IS DISTINCT FROM 'object' THEN RETURN false; END IF;
 IF p ? 'contract_version' THEN RETURN private.valid_music_library_metadata(p,false); END IF;
 IF octet_length(p::text)>16384
  OR (SELECT count(*) FROM jsonb_object_keys(p))<>13
  OR EXISTS(SELECT 1 FROM jsonb_object_keys(p) AS keys(name) WHERE name NOT IN
    ('id','title','creator','provider','source','source_url','audio_url','license','license_url','attribution','duration_ms','start_offset_ms','used_duration_ms')) THEN
  RETURN false;
 END IF;
 IF jsonb_typeof(p->'id') IS DISTINCT FROM 'string'
  OR NOT coalesce(p->>'id' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',false)
  OR jsonb_typeof(p->'title') IS DISTINCT FROM 'string' OR coalesce(length(btrim(p->>'title')),0) NOT BETWEEN 1 AND 300
  OR jsonb_typeof(p->'creator') IS DISTINCT FROM 'string' OR coalesce(length(p->>'creator'),0)>300
  OR jsonb_typeof(p->'provider') IS DISTINCT FROM 'string' OR coalesce(length(btrim(p->>'provider')),0) NOT BETWEEN 1 AND 200
  OR jsonb_typeof(p->'source') IS DISTINCT FROM 'string' OR coalesce(length(btrim(p->>'source')),0) NOT BETWEEN 1 AND 200
  OR jsonb_typeof(p->'attribution') IS DISTINCT FROM 'string' OR coalesce(length(p->>'attribution'),0)>800
  OR jsonb_typeof(p->'license') IS DISTINCT FROM 'string' OR p->>'license' IS DISTINCT FROM 'cc0'
  OR jsonb_typeof(p->'license_url') IS DISTINCT FROM 'string' OR coalesce(length(p->>'license_url'),0) NOT BETWEEN 1 AND 2048
  OR NOT coalesce(p->>'license_url' ~* '^https://creativecommons[.]org/publicdomain/zero/1[.]0/?([?#].*)?$',false)
  OR jsonb_typeof(p->'source_url') IS DISTINCT FROM 'string' OR coalesce(length(p->>'source_url'),0)>2048
  OR NOT coalesce(p->>'source_url' ~* '^https://[^/@[:space:]]+(/|[?#]|$)',false)
  OR jsonb_typeof(p->'audio_url') IS DISTINCT FROM 'string' OR coalesce(length(p->>'audio_url'),0)>2048
  OR NOT coalesce(p->>'audio_url' ~* '^https://[^/@[:space:]]+(/|[?#]|$)',false) THEN
  RETURN false;
 END IF;
 IF jsonb_typeof(p->'duration_ms') IS DISTINCT FROM 'number'
  OR jsonb_typeof(p->'start_offset_ms') IS DISTINCT FROM 'number'
  OR jsonb_typeof(p->'used_duration_ms') IS DISTINCT FROM 'number'
  OR coalesce(p->>'duration_ms','') !~ '^[0-9]{1,8}$'
  OR coalesce(p->>'start_offset_ms','') !~ '^[0-9]{1,8}$'
  OR coalesce(p->>'used_duration_ms','') !~ '^[0-9]{1,5}$' THEN RETURN false; END IF;
 v_duration:=(p->>'duration_ms')::bigint; v_start:=(p->>'start_offset_ms')::bigint; v_used:=(p->>'used_duration_ms')::bigint;
 RETURN v_duration BETWEEN 1000 AND 3600000 AND v_start>=0 AND v_used BETWEEN 1000 AND 60000
   AND v_start<=v_duration-v_used;
EXCEPTION WHEN OTHERS THEN RETURN false;
END $$;

CREATE OR REPLACE FUNCTION private.bind_post_music(p_post_id uuid,p_music_metadata jsonb) RETURNS void
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); p public.posts; fingerprint text;
BEGIN
 IF p_post_id IS NULL OR NOT private.valid_music_metadata(p_music_metadata)
  OR (p_music_metadata ? 'contract_version' AND NOT private.valid_music_library_metadata(p_music_metadata,true)) THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_music_metadata'; END IF;
 fingerprint:=encode(sha256(convert_to(p_music_metadata::text,'UTF8')),'hex');
 SELECT * INTO p FROM public.posts WHERE id=p_post_id AND author_id=actor AND is_reel
   AND group_id IS NULL AND state='published' FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
 IF p.music_metadata_hash IS NOT NULL THEN
  IF p.music_metadata_hash<>fingerprint THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
  RETURN;
 END IF;
 UPDATE public.posts SET music_metadata=p_music_metadata,music_metadata_hash=fingerprint
  WHERE id=p.id AND author_id=actor AND music_metadata_hash IS NULL;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
END $$;

CREATE OR REPLACE FUNCTION private.bind_story_music(p_story_id uuid,p_music_metadata jsonb) RETURNS void
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.c_actor(); s public.stories; fingerprint text;
BEGIN
 IF p_story_id IS NULL OR NOT private.valid_music_metadata(p_music_metadata)
  OR (p_music_metadata ? 'contract_version' AND NOT private.valid_music_library_metadata(p_music_metadata,true)) THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_music_metadata'; END IF;
 fingerprint:=encode(sha256(convert_to(p_music_metadata::text,'UTF8')),'hex');
 SELECT * INTO s FROM public.stories WHERE id=p_story_id AND author_id=actor AND state='active'
   AND expires_at>statement_timestamp() FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='story_unavailable'; END IF;
 IF s.music_metadata_hash IS NOT NULL THEN
  IF s.music_metadata_hash<>fingerprint THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
  RETURN;
 END IF;
 UPDATE public.stories SET music_metadata=p_music_metadata,music_metadata_hash=fingerprint
  WHERE id=s.id AND author_id=actor AND music_metadata_hash IS NULL;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
END $$;

CREATE FUNCTION private.meeto_music_library_object_readable(p_object_path text) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.is_active() AND EXISTS(SELECT 1 FROM public.music_catalog_tracks t
   WHERE t.bucket_id='music-library' AND t.object_path=p_object_path AND t.is_active)
$$;

CREATE FUNCTION private.get_music_catalog_v2(p_catalog_version text,p_after_catalog_version text,
 p_after_sort_order integer,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE rows jsonb; more boolean;
BEGIN
 IF NOT private.is_active() THEN RAISE SQLSTATE 'PT403' USING MESSAGE='active_account_required'; END IF;
 IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 50
  OR (p_after_catalog_version IS NULL)<>(p_after_sort_order IS NULL)
  OR (p_catalog_version IS NOT NULL AND p_after_catalog_version IS NOT NULL AND p_catalog_version<>p_after_catalog_version)
  OR char_length(coalesce(p_catalog_version,''))>80 OR char_length(coalesce(p_after_catalog_version,''))>80
  OR (p_after_sort_order IS NOT NULL AND p_after_sort_order<1) THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_music_catalog_cursor';
 END IF;
 WITH candidates AS MATERIALIZED (
  SELECT t.* FROM public.music_catalog_tracks t
  WHERE t.is_active AND (p_catalog_version IS NULL OR t.catalog_version=p_catalog_version)
   AND (p_after_catalog_version IS NULL OR (t.catalog_version,t.sort_order)>(p_after_catalog_version,p_after_sort_order))
  ORDER BY t.catalog_version,t.sort_order,t.track_id LIMIT p_limit+1
 ), page AS (
  SELECT * FROM candidates ORDER BY catalog_version,sort_order,track_id LIMIT p_limit
 )
 SELECT coalesce(jsonb_agg(jsonb_build_object('track_id',track_id,'catalog_version',catalog_version,
  'sort_order',sort_order,'title',title,'artist',artist,'duration_ms',duration_ms,'bucket_id',bucket_id,
  'object_path',object_path,'mime_type',mime_type,'codec',codec,'container',container,
  'file_size_bytes',file_size_bytes,'sha256',sha256,'license_id',license_id,'license_url',license_url,
  'source_url',source_url,'attribution',attribution) ORDER BY catalog_version,sort_order,track_id),'[]'::jsonb),
  (SELECT count(*)>p_limit FROM candidates) INTO rows,more FROM page;
 RETURN jsonb_build_object('contract_version',2,'items',rows,'has_more',more,
  'next_cursor',CASE WHEN more THEN jsonb_build_object('catalog_version',rows->-1->>'catalog_version',
    'sort_order',(rows->-1->>'sort_order')::integer) END);
END $$;

CREATE FUNCTION private.get_music_library_capabilities() RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE n bigint; versions jsonb;
BEGIN
 IF NOT private.is_active() THEN RAISE SQLSTATE 'PT403' USING MESSAGE='active_account_required'; END IF;
 SELECT count(*),coalesce(jsonb_agg(DISTINCT catalog_version ORDER BY catalog_version),'[]'::jsonb)
  INTO n,versions FROM public.music_catalog_tracks WHERE is_active;
 RETURN jsonb_build_object('contract_version',2,'enabled',n>0,'source','meeto_library',
  'bucket_id','music-library','delivery','authenticated_storage','active_track_count',n,
  'catalog_versions',versions,'max_used_duration_ms',60000);
END $$;

CREATE FUNCTION public.get_music_catalog(p_catalog_version text DEFAULT NULL,
 p_after_catalog_version text DEFAULT NULL,p_after_sort_order integer DEFAULT NULL,p_limit integer DEFAULT 50)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.get_music_catalog_v2($1,$2,$3,$4)
$$;
CREATE FUNCTION public.get_music_library_capabilities() RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$ SELECT private.get_music_library_capabilities() $$;

INSERT INTO storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
VALUES('music-library','music-library',false,5242880,ARRAY['audio/ogg']);

-- A targeted restrictive guard constrains any existing permissive policy for
-- this bucket while leaving all established media buckets' access unchanged.
CREATE POLICY meeto_music_library_anon_deny ON storage.objects AS RESTRICTIVE FOR SELECT TO anon
 USING(bucket_id<>'music-library');
CREATE POLICY meeto_music_library_read_guard ON storage.objects AS RESTRICTIVE FOR SELECT TO authenticated
 USING(bucket_id<>'music-library' OR
  (storage.allow_any_operation(ARRAY['object.get_authenticated','object.get_authenticated_info'])
   AND private.meeto_music_library_object_readable(name)));
CREATE POLICY meeto_music_library_read ON storage.objects FOR SELECT TO authenticated
 USING(bucket_id='music-library'
  AND storage.allow_any_operation(ARRAY['object.get_authenticated','object.get_authenticated_info'])
  AND private.meeto_music_library_object_readable(name));
CREATE POLICY meeto_music_library_no_insert ON storage.objects AS RESTRICTIVE FOR INSERT TO anon,authenticated
 WITH CHECK(bucket_id<>'music-library');
CREATE POLICY meeto_music_library_no_update ON storage.objects AS RESTRICTIVE FOR UPDATE TO anon,authenticated
 USING(bucket_id<>'music-library') WITH CHECK(bucket_id<>'music-library');
CREATE POLICY meeto_music_library_no_delete ON storage.objects AS RESTRICTIVE FOR DELETE TO anon,authenticated
 USING(bucket_id<>'music-library');

REVOKE ALL ON FUNCTION private.valid_music_library_metadata(jsonb,boolean),
 private.meeto_music_library_object_readable(text),private.get_music_catalog_v2(text,text,integer,integer),
 private.get_music_library_capabilities() FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION private.meeto_music_library_object_readable(text) TO authenticated;
REVOKE ALL ON FUNCTION public.get_music_catalog(text,text,integer,integer),
 public.get_music_library_capabilities() FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION public.get_music_catalog(text,text,integer,integer),
 public.get_music_library_capabilities() TO authenticated;

DO $$ BEGIN
 IF has_table_privilege('anon','public.music_catalog_tracks','SELECT')
  OR has_table_privilege('anon','public.music_catalog_tracks','INSERT')
  OR has_table_privilege('anon','public.music_catalog_tracks','UPDATE')
  OR has_table_privilege('anon','public.music_catalog_tracks','DELETE')
  OR has_table_privilege('authenticated','public.music_catalog_tracks','SELECT')
  OR has_table_privilege('authenticated','public.music_catalog_tracks','INSERT')
  OR has_table_privilege('authenticated','public.music_catalog_tracks','UPDATE')
  OR has_table_privilege('authenticated','public.music_catalog_tracks','DELETE')
  OR has_table_privilege('service_role','public.music_catalog_tracks','SELECT')
  OR has_table_privilege('service_role','public.music_catalog_tracks','INSERT')
  OR has_table_privilege('service_role','public.music_catalog_tracks','UPDATE')
  OR has_table_privilege('service_role','public.music_catalog_tracks','DELETE') THEN
  RAISE EXCEPTION 'Music catalog table must remain inaccessible to API roles';
 END IF;
 IF has_function_privilege('anon','public.get_music_catalog(text,text,integer,integer)','EXECUTE')
  OR has_function_privilege('service_role','public.get_music_catalog(text,text,integer,integer)','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.get_music_catalog(text,text,integer,integer)','EXECUTE')
  OR has_function_privilege('anon','public.get_music_library_capabilities()','EXECUTE')
  OR has_function_privilege('service_role','public.get_music_library_capabilities()','EXECUTE')
  OR NOT has_function_privilege('authenticated','public.get_music_library_capabilities()','EXECUTE') THEN
  RAISE EXCEPTION 'Music catalog RPC effective EXECUTE must be authenticated-only';
 END IF;
 IF has_function_privilege('anon','private.meeto_music_library_object_readable(text)','EXECUTE')
  OR has_function_privilege('service_role','private.meeto_music_library_object_readable(text)','EXECUTE')
  OR NOT has_function_privilege('authenticated','private.meeto_music_library_object_readable(text)','EXECUTE') THEN
  RAISE EXCEPTION 'Storage policy helper EXECUTE must be authenticated-only';
 END IF;
 IF has_table_privilege('authenticated','storage.objects','INSERT')
  AND NOT EXISTS(SELECT 1 FROM pg_policies WHERE schemaname='storage' AND tablename='objects'
   AND policyname='meeto_music_library_no_insert' AND permissive='RESTRICTIVE') THEN
  RAISE EXCEPTION 'Authenticated Storage writes need the music-library restrictive guard';
 END IF;
END $$;

NOTIFY pgrst,'reload schema';
COMMIT;

