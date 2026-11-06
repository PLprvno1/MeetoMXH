// Isolated PostgreSQL WASM harness. Managed-service mocks are LOCAL ONLY.
// npm install --prefix build/task36-db @electric-sql/pglite
const {PGlite}=require('../../build/task36-db/node_modules/@electric-sql/pglite');
const fs=require('fs'),assert=require('assert');
(async()=>{
 const db=new PGlite();await db.waitReady;
 await db.exec(`CREATE ROLE anon; CREATE ROLE authenticated; CREATE ROLE service_role BYPASSRLS;
 CREATE SCHEMA auth; CREATE TABLE auth.users(id uuid PRIMARY KEY,email text,email_confirmed_at timestamptz,phone_confirmed_at timestamptz,raw_user_meta_data jsonb DEFAULT '{}',raw_app_meta_data jsonb DEFAULT '{}',created_at timestamptz DEFAULT now());
 CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql AS $$ SELECT nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
 CREATE FUNCTION auth.jwt() RETURNS jsonb LANGUAGE sql AS $$ SELECT jsonb_build_object('sub',auth.uid(),'is_anonymous',false) $$;
 GRANT USAGE ON SCHEMA auth TO authenticated; GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA auth TO authenticated;
 CREATE SCHEMA storage; CREATE TABLE storage.buckets(id text PRIMARY KEY,name text,public boolean,file_size_limit bigint,allowed_mime_types text[]);
 CREATE TABLE storage.objects(id uuid DEFAULT gen_random_uuid() PRIMARY KEY,bucket_id text,name text,owner_id text,metadata jsonb,created_at timestamptz DEFAULT now(),updated_at timestamptz DEFAULT now());
 ALTER TABLE storage.objects ENABLE ROW LEVEL SECURITY;
 CREATE FUNCTION storage.allow_any_operation(text[]) RETURNS boolean LANGUAGE sql AS $$ SELECT true $$;
 CREATE SCHEMA realtime; CREATE TABLE realtime.messages(topic text,extension text,payload jsonb);
 ALTER TABLE realtime.messages ENABLE ROW LEVEL SECURITY;
 CREATE FUNCTION realtime.topic() RETURNS text LANGUAGE sql AS $$ SELECT ''::text $$;
 CREATE FUNCTION realtime.send(jsonb,text,text,boolean) RETURNS void LANGUAGE plpgsql AS $$ BEGIN END $$;
 CREATE FUNCTION realtime.broadcast_changes(text,text,text,text,text,record,record) RETURNS void LANGUAGE plpgsql AS $$ BEGIN END $$;
 CREATE PUBLICATION supabase_realtime;`);
 // Worker transport/cron migrations are excluded; authorization tests do not invoke them.
 const skip=new Set(['20261101001600_cloud_workers.sql','20261101001700_cloud_worker_timeout.sql','20261102000100_chat_broadcast_persistence_check.sql','20261103001500_voice_messaging.sql']);
 for(const file of fs.readdirSync('supabase/migrations').filter(x=>x.endsWith('.sql')).sort()){
  if(skip.has(file))continue;
  try{if(file==='20261103000500_community_groups.sql')await db.exec('SET check_function_bodies=off');if(file==='20261106000100_follow_only_privacy.sql'){
   await db.exec(`INSERT INTO auth.users(id,email_confirmed_at) VALUES('20000000-0000-0000-0000-000000000001',now());
    UPDATE public.profiles SET onboarding_completed=true,display_name='Legacy preservation fixture' WHERE id='20000000-0000-0000-0000-000000000001';
    INSERT INTO public.groups(id,created_by,client_request_id,name) VALUES('20000000-0000-0000-0000-000000000002','20000000-0000-0000-0000-000000000001',gen_random_uuid(),'Legacy community fixture');
    INSERT INTO public.posts(id,author_id,group_id,client_request_id,request_hash,body,audience) VALUES('20000000-0000-0000-0000-000000000003','20000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000002',gen_random_uuid(),repeat('a',64),'Preserved legacy group content','group');`);
  }if(file==='20261106000100_follow_only_privacy.sql')await db.exec('GRANT SELECT (bio,cover_asset_id) ON public.profiles TO PUBLIC,anon,authenticated,service_role');if(file==='20261106000500_profile_field_visibility.sql')await db.exec(`INSERT INTO auth.users(id,email,email_confirmed_at) VALUES('20000000-0000-0000-0000-000000000005','avatar-fixture@local.invalid',now()); UPDATE public.profiles SET onboarding_completed=true,display_name='Avatar migration fixture',public_avatar_preset='male' WHERE id='20000000-0000-0000-0000-000000000005'; UPDATE public.profile_private SET gender='female' WHERE user_id='20000000-0000-0000-0000-000000000005';`);if(file.startsWith('20261106'))await db.exec('SET check_function_bodies=on');await db.exec(fs.readFileSync('supabase/migrations/'+file,'utf8'));console.log('APPLIED local '+file);}
  catch(e){console.error('MIGRATION FAILED '+file+'\n'+e.message);process.exitCode=1;await db.close();return;}
 }
 console.log('Migration compilation complete.');
 let checks=0;const uuid=()=>require('crypto').randomUUID();
 const [A,B,C,D,ADMIN,E]=[1,2,3,4,5,6].map(n=>`10000000-0000-0000-0000-${String(n).padStart(12,'0')}`);
 for(const [id,name]of [[A,'Nguyen An'],[B,'Đặng Hoàng Hải'],[C,'Le Chi'],[D,'Tran Dung'],[ADMIN,'System Admin']]){
  await db.query('INSERT INTO auth.users(id,email,email_confirmed_at) VALUES($1,$2,now())',[id,id+'@local.invalid']);
  await db.query('UPDATE public.profiles SET display_name=$2,onboarding_completed=true,bio=$3 WHERE id=$1',[id,name,'Private biography']);
 }
 await db.query("INSERT INTO public.system_roles(user_id,role) VALUES($1,'system_admin')",[ADMIN]);
 const as=async(id,sql,args=[])=>{await db.exec('RESET ROLE');await db.query("SELECT set_config('request.jwt.claim.sub',$1,false)",[id]);await db.exec('SET ROLE authenticated');return (await db.query(sql,args)).rows;};
 const rpc=async(id,name,args=[])=>{const rows=await as(id,`SELECT public.${name}(${args.map((_,i)=>'$'+(i+1)).join(',')}) AS result`,args);return rows[0].result;};
 const owner=async(sql,args=[])=>{await db.exec('RESET ROLE');return(await db.query(sql,args)).rows;};
 const eq=(actual,expected,label)=>{assert.deepStrictEqual(actual,expected,label);checks++;console.log('PASS '+label);};
 const deny=async(fn,label)=>{let failed=false;try{await fn();}catch(e){if(!/^PT\d\d\d$|^42501$/.test(e.code||''))throw Error(label+': unexpected error '+e.code+' '+e.message);failed=true;}assert(failed,label);checks++;console.log('PASS denied '+label);};
 const suggestions=await rpc(A,'onboarding_suggestions',[40]);
 assert(suggestions.some(x=>x.profile_id===B)&&suggestions.some(x=>x.profile_id===C),'suggestions include eligible public accounts');checks++;console.log('PASS onboarding suggestions include discoverable profiles');
 assert(!suggestions.some(x=>x.profile_id===A)&&!suggestions.some(x=>x.profile_id===ADMIN),'suggestions exclude self and System Admin');checks++;console.log('PASS onboarding suggestions exclude self and protected accounts');
 await rpc(A,'set_follow',[B,true]);
 eq((await rpc(A,'onboarding_suggestions',[40])).find(x=>x.profile_id===B).following,true,'suggestions reflect newly followed account');
 await rpc(A,'set_follow',[B,false]);
 await deny(()=>rpc(A,'onboarding_suggestions',[0]),'suggestion limit validation');
 eq((await owner("SELECT public_avatar_preset FROM public.profiles WHERE id='20000000-0000-0000-0000-000000000005'"))[0].public_avatar_preset,'female','migration aligns existing avatar preset with stored gender');
 const profilePrivs=(await owner(`SELECT
  has_column_privilege('anon','public.profiles','bio','SELECT') anon_bio,
  has_column_privilege('anon','public.profiles','cover_asset_id','SELECT') anon_cover,
  has_column_privilege('authenticated','public.profiles','bio','SELECT') auth_bio,
  has_column_privilege('authenticated','public.profiles','cover_asset_id','SELECT') auth_cover,
  has_column_privilege('service_role','public.profiles','bio','SELECT') service_bio,
  has_column_privilege('authenticated','public.profiles','display_name','SELECT') auth_name,
  (SELECT relrowsecurity FROM pg_class WHERE oid='public.profiles'::regclass) rls,
  EXISTS(SELECT 1 FROM pg_class c,LATERAL aclexplode(c.relacl) a WHERE c.oid='public.profiles'::regclass AND a.grantee=0 AND a.privilege_type='SELECT') public_select`))[0];
 for(const col of ['anon_bio','anon_cover','auth_bio','auth_cover','service_bio','public_select'])eq(profilePrivs[col],false,'effective profile '+col+' privilege revoked');
 eq(profilePrivs.auth_name,true,'safe profile identity column remains selectable');eq(profilePrivs.rls,true,'profiles RLS remains enabled');
 const profilePolicyCount=(await owner("SELECT count(*)::int n FROM pg_policies WHERE schemaname='public' AND tablename='profiles'"))[0].n;
 assert(profilePolicyCount>0,'profiles RLS policy exists');checks++;console.log('PASS profiles RLS policies retained');
 for(const [table,name]of [['posts','posts_audience_check'],['stories','stories_audience_check'],['profile_albums','profile_albums_audience_check'],['user_settings','user_settings_last_seen_audience_check'],['profile_private','profile_private_location_audience_check'],['user_settings','user_settings_tagging_policy_check'],['notifications','notifications_kind_check'],['user_settings','user_settings_prefs_keys']]){
  const row=(await owner('SELECT convalidated FROM pg_constraint WHERE conrelid=$1::regclass AND conname=$2',[`public.${table}`,name]))[0];
  assert(row&&row.convalidated,'expected validated local catalog constraint '+table+'.'+name);checks++;console.log('PASS catalog constraint '+table+'.'+name);
 }
 let p=await rpc(B,'create_post',['public fixture','public',[],uuid()]);let pid=p.id;
 const profileCaps=await rpc(A,'task36_capabilities',[]);
 eq(profileCaps.version,37,'profile fields deployment version advertised');
 eq(profileCaps.profile_field_visibility,true,'profile fields capability advertised');
 eq((await rpc(A,'get_post',[pid])).id,pid,'public post readable');
 eq((await rpc(A,'get_follow_counts',[B])).posts_count,1,'profile includes readable post count');
 let bProfile=await rpc(B,'get_profile',[B]);
 eq(bProfile.birth_date_audience,'private','existing birth date privacy defaults owner-only');
 eq(bProfile.gender_audience,'private','existing gender privacy defaults owner-only');
 let bFields=await rpc(B,'update_profile_v37',[bProfile.display_name,bProfile.bio,bProfile.username,bProfile.avatar_asset_id,bProfile.cover_asset_id,bProfile.location,'private','1990-01-02','public','Nam','private',bProfile.version]);
 eq(bFields.avatar_preset,'male','gender change selects matching avatar preset automatically');
 let visibleFields=await rpc(A,'get_profile',[B]);
 eq(visibleFields.birth_date,'1990-01-02','public birth date is visible');
 eq(visibleFields.gender,null,'private gender stays hidden');
 bFields=await rpc(B,'update_profile_v37',[bFields.display_name,bFields.bio,bFields.username,bFields.avatar_asset_id,bFields.cover_asset_id,bFields.location,'private','1990-01-02','followers','Nam','followers',bFields.version]);
 eq((await rpc(C,'get_profile',[B])).birth_date,null,'follower-only birth date hidden from non-followers');
 eq((await rpc(C,'get_profile',[B])).gender,null,'follower-only gender hidden from non-followers');
 eq((await rpc(A,'search_advanced_people',['Hoang Hai',null,null,30]))[0].profile_id,B,'accentless middle/name search');
 eq((await rpc(A,'search_advanced_people',['Admin',null,null,30])).length,0,'system admin excluded from search');
 await deny(()=>rpc(A,'set_follow',[ADMIN,true]),'follow system admin');
 await deny(()=>rpc(A,'get_or_create_private',[ADMIN]),'message system admin');
 await rpc(B,'update_privacy_settings',['private','private','private']);
 await deny(()=>rpc(A,'get_post',[pid]),'private account overrides old public post');
 eq((await rpc(A,'get_profile',[B])).bio,null,'private basic profile redacts biography');
 eq((await rpc(A,'get_profile',[B])).birth_date,null,'private account gates audience-authorized birth date');
 eq((await rpc(A,'get_profile',[B])).gender,null,'private account gates audience-authorized gender');
 await deny(()=>as(A,'SELECT bio FROM public.profiles WHERE id=$1',[B]),'raw REST biography column');
 let follow=await rpc(A,'set_follow',[B,true]);eq(follow.requested,true,'private follow remains pending');eq(follow.following,false,'pending is not following');
 await deny(()=>rpc(C,'resolve_follow_request',[A,true]),'third party accepting follow');
 await rpc(B,'resolve_follow_request',[A,true]);eq((await rpc(A,'get_post',[pid])).id,pid,'accepted follower reads content');
 let followerProfile=await rpc(A,'get_profile',[B]);
 eq(followerProfile.birth_date,'1990-01-02','approved follower can read audience-authorized birth date');
 eq(followerProfile.gender,'Nam','approved follower can read audience-authorized gender');
 await rpc(B,'remove_follower',[A]);await deny(()=>rpc(A,'get_post',[pid]),'remove follower revokes content');
 await rpc(A,'set_follow',[B,true]);await rpc(B,'update_privacy_settings',['public','private','private']);
 eq((await rpc(A,'get_follow_counts',[B])).requested,true,'public transition preserves pending request without bulk approval');
 let publicFollow=await rpc(A,'set_follow',[B,true]);eq(publicFollow.following,true,'explicit new follow takes effect on public profile');eq(publicFollow.requested,false,'explicit public follow cancels only own stale request');
 eq((await owner('SELECT state FROM public.follow_requests WHERE requester_id=$1 AND recipient_id=$2',[A,B]))[0].state,'canceled','stale follow request canceled, not accepted');
 await rpc(A,'set_follow',[B,false]);
 await deny(()=>rpc(A,'create_post',['new friends','friends',[],uuid()]),'new friendship audience');
 await owner("INSERT INTO public.friendships(user_low,user_high) VALUES($1,$2)",[A,B]);
 let legacyId=uuid();await owner("INSERT INTO public.posts(id,author_id,body,audience,client_request_id,request_hash) VALUES($1,$2,'legacy','friends',$3,repeat('a',64))",[legacyId,B,uuid()]);
 await deny(()=>rpc(A,'get_post',[legacyId]),'legacy friendship grants no content access');
 await deny(()=>rpc(A,'get_or_create_private',[C]),'public stranger direct bypass');
 let requestId=uuid();eq((await rpc(A,'send_message_request',[C,'Hello',requestId,null])).state,'pending','one text introduction');
 eq((await rpc(A,'send_message_request',[C,'Hello',requestId,null])).state,'pending','introduction retry idempotent');
 await deny(()=>rpc(A,'send_message_request',[C,'second',uuid(),null]),'second pending introduction');
 await deny(()=>rpc(A,'resolve_message_request',[A,true]),'sender accepting own request');
 let accepted=await rpc(C,'resolve_message_request',[A,true]);let cid=accepted.conversation_id;
 let history=await rpc(A,'get_messages',[cid,null,null,100]);eq(history.items[0].body,'Hello','accepted introduction retained in history');
 const afterAcceptMessage=await rpc(A,'send_message',[cid,uuid(),'text','After acceptance',[],null]);
 eq((await owner("SELECT count(*)::int n FROM public.notifications WHERE recipient_id=$1 AND event_key=$2 AND kind='message'",[C,'message:'+afterAcceptMessage.id]))[0].n,1,'existing direct message notification trigger still emits');
 await rpc(C,'update_privacy_settings',['private','private','private']);eq((await rpc(A,'get_or_create_private',[C])).id,cid,'accepted conversation survives privacy change');
 await rpc(C,'set_block',[A,true]);await deny(()=>rpc(A,'send_message',[cid,uuid(),'text','blocked',[],null]),'blocked send using old conversation');
 await rpc(C,'set_block',[A,false]);await rpc(C,'update_privacy_settings',['public','private','private']);
 await rpc(A,'send_message_request',[D,'Decline me',uuid(),null]);await rpc(D,'resolve_message_request',[A,false]);await deny(()=>rpc(A,'send_message_request',[D,'retry rejected',uuid(),null]),'rejected request cannot reopen');
 const groupReq=uuid();let group=await rpc(A,'create_group_conversation',['Test group',[C,D],groupReq]);let gid=group.id;
 eq((await owner('SELECT count(*)::int n FROM public.conversation_members WHERE conversation_id=$1',[gid]))[0].n,1,'invitees not auto-added');
 eq((await rpc(A,'create_group_conversation',['Test group',[D,C],groupReq])).id,gid,'group retry member ordering idempotent');
 await deny(()=>rpc(A,'create_group_conversation',['Bad',[C],uuid()]),'group needs two invitees');
 await deny(()=>rpc(A,'create_group_conversation',['Bad',[C,C],uuid()]),'duplicate selected invitees');
 await deny(()=>rpc(C,'get_messages',[gid,null,null,100]),'pending invite history');
 await deny(()=>rpc(C,'send_message',[gid,uuid(),'text','before accept',[],null]),'pending invite send');
 await rpc(C,'resolve_conversation_invitation',[gid,true]);await rpc(D,'resolve_conversation_invitation',[gid,false]);
 eq((await rpc(C,'send_message',[gid,uuid(),'text','group hello',[],null])).body,'group hello','active group sends message');
 await deny(()=>rpc(C,'manage_conversation_member',[gid,D,'add']),'regular member managing group');
 await deny(()=>rpc(A,'manage_conversation_member',[gid,C,'add']),'active member duplicate invitation');
 await rpc(C,'set_conversation_archived',[gid,true]);
 eq((await rpc(C,'get_inbox',[null,null,50,false])).some(x=>x.id===gid),false,'archive removes own main inbox');
 eq((await rpc(A,'get_inbox',[null,null,50,false])).some(x=>x.id===gid),true,'archive does not affect another member');
 eq((await rpc(C,'get_inbox',[null,null,50,true])).find(x=>x.id===gid).archived,true,'archive projection is per member');
 await rpc(C,'set_conversation_archived',[gid,false]);await rpc(C,'leave_conversation',[gid]);
 await deny(()=>rpc(C,'send_message',[gid,uuid(),'text','after leave',[],null]),'left member send');
 eq((await rpc(C,'get_inbox',[null,null,50,true])).some(x=>x.id===gid),false,'left group removed from inbox');
 await deny(()=>rpc(C,'resolve_conversation_invitation',[gid,true]),'accepted stale invitation cannot rejoin after leave');
 await deny(()=>rpc(ADMIN,'admin_set_account_status',[ADMIN,'suspend','test',uuid()]),'admin self suspension');
 const groupRpcNames=['create_group_conversation','resolve_conversation_invitation','manage_conversation_member','set_conversation_avatar','leave_conversation','search_conversations_v36'];
 for(const fn of groupRpcNames){const row=(await owner(`SELECT has_function_privilege('authenticated',p.oid,'EXECUTE') allowed FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.proname=$1 ORDER BY p.oid DESC LIMIT 1`,[fn]))[0];assert(row&&row.allowed,'Messenger Group RPC remains callable: '+fn);checks++;console.log('PASS Messenger Group RPC grant '+fn);}
 const retired=(await owner("SELECT array_agg(n.nspname||'.'||p.proname ORDER BY n.nspname,p.proname) names FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname IN ('public','private') AND p.proname LIKE '%group%' AND p.proname NOT LIKE '%conversation%' AND NOT has_function_privilege('authenticated',p.oid,'EXECUTE')"))[0].names;
 assert(retired&&retired.length>0,'community group RPC family revoked');checks++;console.log('PASS only non-conversation group RPC family retired');
 const report=uuid();await owner("INSERT INTO public.reports(id,reporter_id,client_request_id,target_kind,post_id,reason_code) VALUES($1,$2,$3,'post',$4,'spam')",[report,C,uuid(),pid]);
 await deny(()=>rpc(A,'resolve_platform_report',[report,'hide','not an admin']),'regular user resolving report');
 await rpc(ADMIN,'resolve_platform_report',[report,'hide','fixture only']);await deny(()=>rpc(A,'get_post',[pid]),'hide report actually removes post visibility');
 eq((await owner('SELECT count(*)::int n FROM public.moderation_audit WHERE report_id=$1',[report]))[0].n,1,'atomic report audit');
 eq((await rpc(ADMIN,'resolve_platform_report',[report,'hide','fixture only'])).decision,'hide','report retry idempotent');
 // Registry-only media fixtures test SQL authorization, not actual file decoding or Storage HTTP.
 let mediaPost=await rpc(B,'create_post',['media fixture','public',[],uuid()]);
 const feedShare=await rpc(A,'share_post',[mediaPost.id,'share notification fixture','public',uuid()]);
 eq((await owner("SELECT count(*)::int n FROM public.notifications WHERE recipient_id=$1 AND event_key=$2 AND kind='share'",[B,'share:'+feedShare.id]))[0].n,1,'existing post-share notification trigger still emits');
 const aid=uuid(),storyAsset=uuid(),avatarAsset=uuid(),coverAsset=uuid();
 for(const [id,purpose,bucket]of [[aid,'post','social-media'],[storyAsset,'story','social-media'],[avatarAsset,'avatar','profile-media'],[coverAsset,'cover','profile-media']]){
  await owner("INSERT INTO public.media_assets(id,owner_id,client_request_id,bucket_id,object_path,purpose,mime_type,state,size_bytes,ready_at) VALUES($1,$2,$3,$4,$5,$6,'image/jpeg','ready',100,now())",[id,B,uuid(),bucket,'local-test/'+id,purpose]);
 }
 await owner('INSERT INTO public.post_media(post_id,asset_id,position) VALUES($1,$2,0)',[mediaPost.id,aid]);
 await owner("UPDATE public.media_assets SET created_at='2000-01-01' WHERE id=$1",[aid]);
 await rpc(ADMIN,'admin_moderate_content',['post',mediaPost.id,'hide','cleanup restore regression',uuid()]);
 await owner('SELECT private.schedule_media_cleanup()');
 eq((await owner('SELECT state FROM public.media_assets WHERE id=$1',[aid]))[0].state,'ready','cleanup preserves media referenced by hidden restorable post');
 await rpc(ADMIN,'admin_moderate_content',['post',mediaPost.id,'restore','cleanup restore regression',uuid()]);
 eq((await rpc(B,'get_post',[mediaPost.id])).media[0].asset_id,aid,'hidden post restores with its original media');
 await owner('UPDATE public.profiles SET avatar_asset_id=$2,cover_asset_id=$3 WHERE id=$1',[B,avatarAsset,coverAsset]);
 let story=uuid();await owner("INSERT INTO public.stories(id,author_id,media_asset_id,audience,client_request_id) VALUES($1,$2,$3,'public',$4)",[story,B,storyAsset,uuid()]);
 let album=await rpc(B,'create_profile_album',['Holiday','', 'public']);await rpc(B,'add_profile_album_photos',[album.id,mediaPost.id,[aid]]);
 eq((await rpc(C,'get_profile_album',[album.id,null,null,30])).items.length,1,'public album authorized source photo');
 eq((await as(C,'SELECT id FROM public.media_assets WHERE id=$1',[aid])).length,1,'public raw media registry authorized');
 await rpc(B,'update_privacy_settings',['private','private','private']);
 await deny(()=>rpc(C,'get_profile_album',[album.id,null,null,30]),'private account public album');
 eq((await as(C,'SELECT id FROM public.media_assets WHERE id=$1',[aid])).length,0,'private post raw media registry denied');
 eq((await as(C,'SELECT id FROM public.media_assets WHERE id=$1',[storyAsset])).length,0,'private story raw media registry denied');
 eq((await as(C,'SELECT id FROM public.media_assets WHERE id=$1',[coverAsset])).length,0,'private cover raw media registry denied');
 eq((await as(C,'SELECT id FROM public.media_assets WHERE id=$1',[avatarAsset])).length,1,'private account basic avatar remains discoverable');
 eq((await rpc(C,'get_stories',[B,100])).length,0,'private story feed excluded');
 await deny(()=>rpc(C,'send_message_request',[B,'bypass private',uuid(),null]),'private account message request bypass');
 await rpc(B,'update_privacy_settings',['public','private','private']);
 const storyRequest=uuid();let storyReply=await rpc(D,'reply_to_story',[story,storyRequest,'Story introduction']);eq(storyReply.request_state,'pending','public stranger story reply is a request');
 let storyChat=await rpc(B,'resolve_message_request',[D,true]);let storyMessages=await rpc(D,'get_messages',[storyChat.conversation_id,null,null,100]);
 eq(storyMessages.items[0].reply_story.caption,'','story reply projected with authorized quote');
 eq(storyMessages.items[0].reply_story_id,story,'story ID retained');
 let newGroup=await rpc(A,'create_group_conversation',['Avatar group',[B,C],uuid()]);
 await rpc(B,'resolve_conversation_invitation',[newGroup.id,true]);
 await deny(()=>rpc(B,'set_conversation_avatar',[newGroup.id,avatarAsset]),'member changing group avatar');
 let groupAvatar=uuid();await owner("INSERT INTO public.media_assets(id,owner_id,client_request_id,bucket_id,object_path,purpose,mime_type,state,size_bytes,ready_at) VALUES($1,$2,$3,'profile-media',$4,'avatar','image/jpeg','ready',100,now())",[groupAvatar,A,uuid(),'group-avatar/'+groupAvatar]);
 await rpc(A,'set_conversation_avatar',[newGroup.id,groupAvatar]);
 eq((await as(B,'SELECT id FROM public.media_assets WHERE id=$1',[groupAvatar])).length,1,'active group avatar readable');
 eq((await as(D,'SELECT id FROM public.media_assets WHERE id=$1',[groupAvatar])).length,0,'outsider group avatar denied');
 await rpc(B,'set_block',[A,true]);await deny(()=>rpc(A,'send_message',[newGroup.id,uuid(),'text','group bypass',[],null]),'group block cannot bypass direct block');
 await rpc(B,'set_block',[A,false]);
 await rpc(A,'leave_conversation',[newGroup.id]);eq((await owner('SELECT owner_id FROM public.conversations WHERE id=$1',[newGroup.id]))[0].owner_id,B,'owner departure transfers ownership');
 await deny(()=>rpc(B,'manage_conversation_member',[newGroup.id,B,'demote']),'owner cannot demote self');
 await deny(()=>rpc(A,'get_friends',[null,null,30]),'retired friend RPC revoked');
 let self=await rpc(B,'get_my_profile',[]);
 let named=await rpc(B,'update_profile_public_v36',[self.display_name,self.bio,'hoang.hai',self.avatar_asset_id,self.cover_asset_id,self.location,'private',self.version]);
 eq(named.username,'hoang.hai','username saved with profile transaction');
 eq((await rpc(C,'search_advanced_people',['@hoang.hai',null,null,30]))[0].profile_id,B,'username search');
 const cSelf=await rpc(C,'get_my_profile',[]);
 await deny(()=>rpc(C,'update_profile_public_v36',['Should rollback',cSelf.bio,'hoang.hai',cSelf.avatar_asset_id,cSelf.cover_asset_id,cSelf.location,'private',cSelf.version]),'duplicate username');
 eq((await rpc(C,'get_my_profile',[])).display_name,cSelf.display_name,'username conflict rolls back entire profile update');
 let commentRequest=uuid();let comment=await rpc(C,'create_comment',[mediaPost.id,null,'Count fixture',commentRequest]);
 eq((await rpc(C,'get_post',[mediaPost.id])).comments_count,1,'comment count reconciles from server');
 await rpc(C,'create_comment',[mediaPost.id,null,'Count fixture',commentRequest]);eq((await rpc(C,'get_post',[mediaPost.id])).comments_count,1,'comment retry does not double increment');
 await rpc(C,'create_comment',[mediaPost.id,comment.id,'Reply fixture',uuid()]);eq((await rpc(C,'get_post',[mediaPost.id])).comments_count,2,'reply updates aggregate count');
 await rpc(C,'delete_comment',[comment.id,comment.version]);eq((await rpc(C,'get_post',[mediaPost.id])).comments_count,1,'comment deletion reconciles aggregate count');
 let adminChat=uuid();await owner("INSERT INTO public.conversations(id,kind,direct_low,direct_high,created_by) VALUES($1,'private',$2,$3,$2)",[adminChat,A,ADMIN]);await owner('INSERT INTO public.conversation_members(conversation_id,user_id) VALUES($1,$2),($1,$3)',[adminChat,A,ADMIN]);
 await deny(()=>rpc(A,'send_message',[adminChat,uuid(),'text','legacy admin bypass',[],null]),'old direct ID cannot message system admin');
 let pendingChat=uuid();await owner("INSERT INTO public.conversations(id,kind,direct_low,direct_high,created_by,direct_consent) VALUES($1,'private',$2,$3,$2,'pending')",[pendingChat,B,C]);await owner('INSERT INTO public.conversation_members(conversation_id,user_id) VALUES($1,$2),($1,$3)',[pendingChat,B,C]);
 await deny(()=>rpc(B,'send_message',[pendingChat,uuid(),'text','legacy pending bypass',[],null]),'unconsented legacy direct ID cannot send');
 await deny(()=>rpc(C,'get_messages',[pendingChat,null,null,30]),'unconsented legacy direct ID cannot read');
 await rpc(B,'update_privacy_settings',['private','private','private']);
 eq((await as(ADMIN,'SELECT id FROM public.media_assets WHERE id=$1',[aid])).length,0,'admin cannot read unrelated private media');
 let mediaReport=uuid();await owner("INSERT INTO public.reports(id,reporter_id,client_request_id,target_kind,post_id,reason_code) VALUES($1,$2,$3,'post',$4,'spam')",[mediaReport,A,uuid(),mediaPost.id]);
 eq((await rpc(ADMIN,'admin_report_detail',[mediaReport])).media[0].asset_id,aid,'admin report contains authorized media preview');
 eq((await as(ADMIN,'SELECT id FROM public.media_assets WHERE id=$1',[aid])).length,1,'reported private media readable by authorized admin');
 eq((await as(C,'SELECT id FROM public.media_assets WHERE id=$1',[aid])).length,0,'report does not expose media to regular user');
 let protectedReport=uuid();await owner("INSERT INTO public.reports(id,reporter_id,client_request_id,target_kind,profile_id,reason_code) VALUES($1,$2,$3,'profile',$4,'other')",[protectedReport,A,uuid(),ADMIN]);
 await deny(()=>rpc(ADMIN,'resolve_platform_report',[protectedReport,'suspend','protected fixture']),'report cannot suspend protected admin');
 eq((await owner('SELECT state FROM public.reports WHERE id=$1',[protectedReport]))[0].state,'open','failed moderation leaves report open');
 eq((await owner('SELECT count(*)::int n FROM public.moderation_audit WHERE report_id=$1',[protectedReport]))[0].n,0,'failed moderation creates no success audit');
 await owner("INSERT INTO public.system_roles(user_id,role) VALUES($1,'system_admin')",[D]);
 let adminTargetPost=await rpc(B,'create_post',['protected admin content','public',[],uuid()]);await owner('UPDATE public.posts SET author_id=$2 WHERE id=$1',[adminTargetPost.id,D]);
 await deny(()=>rpc(ADMIN,'admin_moderate_content',['post',adminTargetPost.id,'hide','other System Admin',uuid()]),'content moderation protects another System Admin');
 let adminOwnPost=await rpc(B,'create_post',['self admin content','public',[],uuid()]);await owner('UPDATE public.posts SET author_id=$2 WHERE id=$1',[adminOwnPost.id,ADMIN]);
 await deny(()=>rpc(ADMIN,'admin_moderate_content',['post',adminOwnPost.id,'hide','self System Admin',uuid()]),'content moderation protects acting System Admin target');
 eq((await owner('SELECT state FROM public.posts WHERE id=$1',[adminTargetPost.id]))[0].state,'published','protected admin content unchanged');
 let prefs=await rpc(C,'update_notification_settings',[true,{mention:true,tag:true,follow_request:true,message_request:true,group_invite:true,share:true,message:true,moderation:true}]);eq(prefs.preferences.group_invite,true,'new notification settings accepted');
 for(const kind of ['share','message','moderation']){
  await owner('SELECT private.emit_notification($1,$2,$3,$4,$5,$6)',[C,A,kind,kind==='message'?'conversation':kind==='share'?'post':'profile',kind==='message'?cid:pid,'task36-whitelist:'+kind]);
  eq((await owner('SELECT count(*)::int n FROM public.notifications WHERE recipient_id=$1 AND event_key=$2',[C,'task36-whitelist:'+kind]))[0].n,1,'emit_notification accepts '+kind+' contract kind');
 }
 eq((await rpc(C,'get_explore_posts',[null,null,30])).some(x=>x.id===mediaPost.id),false,'private media excluded from Explore');
 await rpc(B,'set_conversation_archived',[newGroup.id,true]);eq((await rpc(B,'search_conversations_v36',['Avatar',null,null,30,true]))[0].id,newGroup.id,'archived search filters before pagination');
 eq((await rpc(B,'search_conversations_v36',['Avatar',null,null,30,false])).length,0,'main search excludes own archives');
 await rpc(B,'update_privacy_settings',['public','private','private']);
 let reelPost=await rpc(B,'create_post',['reel authorization fixture','public',[],uuid()]);const videoAsset=uuid();
 await owner("UPDATE public.posts SET is_reel=true,reel_duration_ms=1000,reel_cover_position_ms=0,reel_request_hash=repeat('b',64) WHERE id=$1",[reelPost.id]);
 await owner("INSERT INTO public.media_assets(id,owner_id,client_request_id,bucket_id,object_path,purpose,mime_type,state,size_bytes,ready_at) VALUES($1,$2,$3,'social-media',$4,'post','video/mp4','ready',100,now())",[videoAsset,B,uuid(),'reel-fixture/'+videoAsset]);
 await owner('INSERT INTO public.post_media(post_id,asset_id,position) VALUES($1,$2,0)',[reelPost.id,videoAsset]);
 eq((await rpc(C,'get_user_reels',[B,null,null,30]))[0].id,reelPost.id,'public Reel authorized');
 let shared=await rpc(A,'send_shared_post',[cid,reelPost.id,uuid()]);eq(shared.shared_post.author.display_name,'Đặng Hoàng Hải','shared content retains source author');eq(shared.shared_post.is_reel,true,'shared Reel retains content type');
 await rpc(A,'set_saved_post',[reelPost.id,true]);
 await rpc(B,'update_privacy_settings',['private','private','private']);
 eq((await rpc(C,'get_user_reels',[B,null,null,30])).length,0,'private Reel excluded for unapproved viewer');
 let sharedHistory=await rpc(C,'get_messages',[cid,null,null,100]);let protectedShare=sharedHistory.items.find(x=>x.id===shared.id);
 eq(protectedShare.shared_post_available,false,'shared message cannot bypass changed account privacy');eq(protectedShare.shared_post,null,'private shared caption and media are redacted');
 eq((await rpc(A,'get_saved_posts',[null,null,30])).some(x=>x.post&&x.post.id===reelPost.id),false,'saved reference grants no extra access');
 eq((await as(C,'SELECT id FROM public.media_assets WHERE id=$1',[videoAsset])).length,0,'private Reel raw media denied');
 eq((await owner("SELECT body FROM public.posts WHERE id='20000000-0000-0000-0000-000000000003'"))[0].body,'Preserved legacy group content','legacy group data survives forward migrations');
 await deny(()=>rpc(A,'get_post',['20000000-0000-0000-0000-000000000003']),'legacy group post not exposed');
 await deny(()=>rpc(ADMIN,'admin_list_content',['groups','',null,null,null,30]),'community moderation listing retired');
 await rpc(B,'update_privacy_settings',['public','private','private']);
 let followersPost=await rpc(B,'create_post',['followers fixture','followers',[],uuid()]);await deny(()=>rpc(C,'get_post',[followersPost.id]),'new follower audience excludes strangers');
 await rpc(C,'set_follow',[B,true]);eq((await rpc(C,'get_post',[followersPost.id])).id,followersPost.id,'new follower audience accepts current follower');
 await owner("INSERT INTO public.system_roles(user_id,role) VALUES('20000000-0000-0000-0000-000000000001','system_admin')");
 await deny(()=>rpc(ADMIN,'admin_set_account_status',['20000000-0000-0000-0000-000000000001','suspend','other admin fixture',uuid()]),'admin cannot suspend another system admin');
 await rpc(B,'manage_conversation_member',[newGroup.id,C,'add']);await rpc(C,'resolve_conversation_invitation',[newGroup.id,true]);await rpc(B,'manage_conversation_member',[newGroup.id,C,'promote']);
 eq((await rpc(C,'get_conversation',[newGroup.id])).role,'admin','group member promotion');
 await deny(()=>rpc(C,'manage_conversation_member',[newGroup.id,B,'demote']),'group admin cannot demote owner');
 await rpc(B,'manage_conversation_member',[newGroup.id,C,'demote']);eq((await rpc(C,'get_conversation',[newGroup.id])).role,'member','group member demotion');
 const chatAsset=uuid();await owner("INSERT INTO public.media_assets(id,owner_id,client_request_id,bucket_id,object_path,purpose,mime_type,state,size_bytes,ready_at,metadata) VALUES($1,$2,$3,'chat-media',$4,'message','image/jpeg','ready',100,now(),jsonb_build_object('conversation_id',$5::text))",[chatAsset,B,uuid(),'chat-fixture/'+chatAsset,newGroup.id]);
 let groupImage=await rpc(B,'send_message',[newGroup.id,uuid(),'media','',[chatAsset],null]);eq(groupImage.media[0].asset_id,chatAsset,'active group accepts authorized media reference');
 eq((await as(C,'SELECT id FROM public.media_assets WHERE id=$1',[chatAsset])).length,1,'active group recipient can read message media');
 let groupShare=await rpc(B,'send_shared_post',[newGroup.id,followersPost.id,uuid()]);eq(groupShare.shared_post.id,followersPost.id,'group shared post retains original reference');
 await rpc(C,'leave_conversation',[newGroup.id]);eq((await as(C,'SELECT id FROM public.media_assets WHERE id=$1',[chatAsset])).length,0,'left member cannot read old group media URL');
 await deny(()=>rpc(ADMIN,'send_message_request',[A,'admin social request',uuid(),null]),'system admin cannot initiate ordinary message requests');
 await deny(()=>rpc(ADMIN,'create_group_conversation',['admin social group',[A,B],uuid()]),'system admin cannot enter ordinary group creation');
 // An unverified account can still bootstrap/complete its profile and inspect verification state,
 // while chat consent endpoints remain closed until Auth confirms the address.
 await owner('INSERT INTO auth.users(id,email,email_confirmed_at) VALUES($1,$2,NULL)',[E,E+'@local.invalid']);
 await owner("UPDATE public.profiles SET display_name='Unverified Fixture' WHERE id=$1",[E]);
 const pendingProfile=await rpc(E,'get_my_profile',[]);eq(pendingProfile.email_verified,false,'unverified onboarding profile remains readable');
 const pendingVersion=(await owner('SELECT version FROM public.profiles WHERE id=$1',[E]))[0].version;
 await deny(()=>rpc(E,'complete_onboarding',['Unverified Fixture','2000-01-01','unspecified',pendingVersion]),'unverified cannot bypass existing onboarding verification gate');
 await deny(()=>rpc(E,'send_message_request',[D,'must verify first',uuid(),null]),'unverified account cannot enter chat request gate');
 await owner('UPDATE auth.users SET email_confirmed_at=now() WHERE id=$1',[E]);
 await rpc(E,'complete_onboarding',['Unverified Fixture','2000-01-01','unspecified',pendingVersion]);
 eq((await rpc(E,'get_my_profile',[])).onboarding_completed,true,'confirmed account completes onboarding');
 eq((await rpc(E,'send_message_request',[C,'verified now',uuid(),null])).state,'pending','verified account recovers through confirmation before chat');
 await db.exec('RESET ROLE; CREATE SCHEMA supabase_migrations; CREATE TABLE supabase_migrations.schema_migrations(version text PRIMARY KEY)');
 const catalogStatements=fs.readFileSync('supabase/tests/task36-cloud-smoke.sql','utf8').replace(/--[^\r\n]*/g,'').split(';')
  .map(s=>s.trim()).filter(Boolean);
 for(const statement of catalogStatements){assert(/^SELECT\b/i.test(statement),'Cloud smoke file must remain SELECT-only: '+statement.slice(0,80));try{await owner(statement);}catch(e){throw Error('Local catalog query failed: '+statement+'\n'+e.message);}}
 checks+=catalogStatements.length;console.log('PASS read-only catalog query syntax/targets ('+catalogStatements.length+' SELECT statements; local catalog only)');
 console.log('LOCAL SQL PASS: '+checks+' assertions. Managed Auth/Storage/Realtime are mocked; Cloud and concurrency NOT VERIFIED.');
 await db.close();
})().catch(e=>{console.error(e.message);process.exit(1);});
