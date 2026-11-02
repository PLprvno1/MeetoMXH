# Meeto — audit trước triển khai Supabase, nhiệm vụ04
Ngày01/11/2026, Asia/Bangkok. Workspace F:\MeetoMXH.

**Kết luận: NOT READY để triển khai Supabase project thật.** DDL/RLS baseline đã chạy và kiểm thử trên PostgreSQL16.11 local; có thể chuyển sang bước implementation/staging có kiểm soát sau review. Mutation RPC/worker/Auth/Storage/Realtime HTTP integration, product decisions và migration approvals chưa hoàn tất. Đây là audit hoàn thành, không phải từ chối kiểm chứng hoặc tuyên bố backend đã triển khai.

Theo yêu cầu mới: **không sửa baocao.txt**. Nhật ký mới: [baocao_nhiemvu04.txt](../baocao_nhiemvu04.txt). Báo cáo lịch sử01/03 được giữ nguyên, kể cả các nhận định thời điểm cũ.

## 1. Phạm vi, phương pháp và môi trường thực
Đọc10 tệp thiết kế, toàn bộ DDL/RLS/dictionary/ERD; đối chiếu baocao.txt, contracts Java/XML/Auth/Firestore/Storage và hai bộ Use Case đã khảo sát. Không đổi36 bảng vì naming/style. Source vẫn FirebaseAuth/Firestore và StorageREST; chưa có implementation Supabase DB trong Android.

Không có postgres/psql/docker trên PATH, WSL chưa cài. Đã tạo **PostgreSQL portable16.11** từ binary artifact Zonky MavenCentral (22.929.730 byte, đối chiếu SHA-512), giải nén vào thư mục tạm MeetoAudit04-<UUID>, không cài service/system package, không thay dependency project.
- Server bind127.0.0.1:55439, UTC; isolated DB meeto_audit04 (baseline probes), meeto_audit04_final (fresh final tests).
- Auth/Storage/Realtime managed schemas được mô phỏng tối thiểu bằng SQL **stubs**; fake identities/content chỉ là fixtures kiểm thử, không data Meeto/production.
- Dùng libpq từ package qua PowerShell/.NET PInvoke vì binary package không có psql. Runner kiểm host/database/data_directory thuộc tempcluster, không đọc local.properties/credential.
- Guard của SQL gốc giữ nguyên. Bản copy local có local-cluster guard và COMMIT thay ROLLBACK để thử engine. Không chạy SQL trên Supabase/stagingremote thật, không Supabase Console/secret key.
- Cuối nhiệm vụ dừng portable server; không để service nền chạy. Evidence lưu validation/MEETO_LOCAL_AUDIT_RESULTS.json.

## 2. Kết quả kiểm tra36 bảng
**PG16.11 local:**36 tables/267 columns;79 foreign keys;104 CHECK;110 indexes tổng (50 explicit +60 PK/UNIQUE implicit).36 RLS ON;34 SELECT policies; event_outbox/migration_id_map server-only.21 authorization helper functions, safe search_path/explicit grants. Các numbers lấy từ pg_catalog, khác với83 cạnh sơ đồ cũ vì4 cạnh FOREIGN giả đã bị xóa.

| Bảng | Nhóm | FK | CHECK | Index tổng | RLS/quyền đọc | Invariant/enforcement và phần còn phải lập trình |
|---|---|---:|---:|---:|---|---|
| account_status | Tài khoản | 1 | 1 | 1 | ON / scoped SELECT | Self read locked được; suspend/delete server-only, chưa workflow. |
| appeals | Kiểm duyệt | 3 | 2 | 3 | ON / scoped SELECT | Unique(action,appellant); assigned reviewer/locked support pending. |
| comment_reactions | Bài viết | 2 | 1 | 2 | ON / scoped SELECT | OPTIONAL; read follows comment ACL, writer pending. |
| comments | Bài viết | 3 | 7 | 5 | ON / scoped SELECT | Same-post parent FK, no-self và deletedbodyNULL; depth/cycle immutability RPC. |
| conversation_members | Chat | 2 | 6 | 2 | ON / scoped SELECT | Read<=delivered CHECK; boundaries/maxseq/role transitions RPC. |
| conversations | Chat | 4 | 7 | 4 | ON / scoped SELECT | Privatepairunique; circular hint sameconversation FK deferred, cascade đã test. |
| device_subscriptions | Thông báo | 1 | 3 | 4 | ON / scoped SELECT | Endpoint column denied; real registration/SSRF/provider pending. |
| event_outbox | Hạ tầng | 0 | 3 | 3 | ON / server only | No Android grants/policy; lease/retry worker pending. |
| friend_requests | Quan hệ | 2 | 3 | 5 | ON / scoped SELECT | Pending canonicalpair unique đã race; accept transition RPC pending. |
| friendships | Quan hệ | 2 | 1 | 2 | ON / scoped SELECT | Ordered pair PK; uniqueness no self; list privacy projection pending. |
| group_join_requests | Nhóm | 3 | 2 | 3 | ON / scoped SELECT | Partial unique pending; approve/membership transaction pending. |
| group_members | Nhóm | 2 | 2 | 3 | ON / scoped SELECT | Role/state PK scoped; last operative admin recovery pending. |
| groups | Nhóm | 2 | 5 | 2 | ON / scoped SELECT | Active/archive/delete và last-admin không CHECK cross-row. |
| hidden_posts | Bài viết | 2 | 0 | 1 | ON / scoped SELECT | Owner preference only; feed anti-join RPC pending. |
| media_assets | Media | 1 | 6 | 5 | ON / scoped SELECT | Bucket/purpose/checksize, parent-purpose/owner helper fixed; HTTP finalize pending. |
| message_hidden | Chat | 2 | 0 | 2 | ON / scoped SELECT | Composite FK message+member; only own hiddenstate; rawmessagehide tested. |
| message_media | Media | 2 | 1 | 3 | ON / scoped SELECT | Orderedassetunique; message-parentpermission; revoke physicallyasyncpending. |
| messages | Chat | 4 | 9 | 8 | ON / scoped SELECT | Seq/requestunique, samechatreply/redaction; RPC body chưa có, no fullpayloadpublication. |
| migration_id_map | Migration | 0 | 1 | 3 | ON / server only | Server-only mapping, polymorphic target requires reconciliation. |
| moderation_actions | Kiểm duyệt | 2 | 2 | 3 | ON / scoped SELECT | ReportFKRESTRICT; action/idempotencyunique; privileged workflow pending. |
| moderation_audit | Kiểm duyệt | 3 | 1 | 3 | ON / scoped SELECT | FKsnullable/appendonly bygrants; retention job pending. |
| notifications | Thông báo | 2 | 2 | 5 | ON / scoped SELECT | Recipient/keyunique; server eventwriter+deeplink reauth pending. |
| post_media | Media | 2 | 1 | 3 | ON / scoped SELECT | Orderedpositions<=10, assetunique; nonempty/content cross-rowRPC. |
| post_reactions | Bài viết | 2 | 1 | 2 | ON / scoped SELECT | One(user,post), typescheck; counterpattern2writers pass, actual RPC pending. |
| posts | Bài viết | 3 | 12 | 6 | ON / scoped SELECT | Audience/group/share checks; metadata hash column deny; counts RPC chưa có. |
| profile_private | Tài khoản | 1 | 2 | 1 | ON / scoped SELECT | DOB/calendar/tuổi do RPC, không direct UPDATE. |
| profiles | Tài khoản | 3 | 3 | 2 | ON / scoped SELECT | Avatar/cover FK thêm sau media; private fields tách; profile mutation RPC pending. |
| reports | Kiểm duyệt | 8 | 6 | 6 | ON / scoped SELECT | Typed FK/<=1 target CHECK; exactly1atcreate and roleassignmentRPC. |
| saved_posts | Bài viết | 2 | 0 | 2 | ON / scoped SELECT | Owner insert/delete + parent readable, no extra read permission; timestampserver. |
| stories | Stories | 2 | 4 | 6 | ON / scoped SELECT | Expiry24h CHECK; asset UNIQUE mới, FK media thêm sau. |
| story_reactions | Stories | 2 | 1 | 2 | ON / scoped SELECT | Unique perstory/user; expire-state writer pending. |
| story_views | Stories | 2 | 0 | 2 | ON / scoped SELECT | Unique view; expiry/auth record-view RPC pending. |
| system_roles | Kiểm duyệt | 2 | 1 | 1 | ON / scoped SELECT | Self SELECT only, DML denied; provisioningauditpending. |
| user_blocks | Quan hệ | 2 | 1 | 2 | ON / scoped SELECT | No-self PK; block cleanup quan hệ phải RPC transaction. |
| user_follows | Quan hệ | 2 | 1 | 2 | ON / scoped SELECT | undefined |
| user_settings | Tài khoản | 1 | 6 | 1 | ON / scoped SELECT | Direct owner UPDATE đã test;3 CHECK JSON mới. |

### Điều engine enforce thật
PK/FK/UNIQUE/partial-index/check/defaults; same-post/same-conversation references; canonical pending/directpair uniqueness; requestUUID uniqueness; reaction identity; bodyNULL tombstone; shape/audience/type/size constraints. INSERT/UPDATE/DELETE thông thường bị grants/RLS deny nếu chưa mở; không phải “UI ẩn nút”.

### Điều chưa được DDL tự enforce
Last-admin/operative-admin, private chat exactly2 member rows, maxreadseq<=conversation,nextseq allocation, desired-statecounterdelta, parent depth/cycle prevention khi privileged reparent, exactly1 reporttargetatcreate, shareonlyreadableoriginalroot, media ownership/purpose link integrity, serverMIME/quotas, validtimetransition, outboxworker/push idempotency. Chúng cần RPC/jobs thực thi đúng contracts; không công bố đã xong.

### Vòng tham chiếu và xóa
- profiles <-> media_assets: create profiles với ảnhnullable, media registry sau, avatar/cover FK ALTER sau. OwnerFKSETNULL tách cleanup bytes, không FK giả vào storage.objects.
- conversations <-> messages: conversation lastmessage nullable; FK(last_message_id,id) deferred tới messages(id,conversation_id). Pointers không chứa textpreview. Local đã test hard-deleteconversation cascade messages có reply self-FK và hard-deletepost cascade commentthread trong subtransactions, rollback fixtures.
- Auth accountdelete bị directpair FK RESTRICT tới khi privatechatpolicy được xử lý. Đây là guard có chủ đích, không thể bỏ qua bằng cascade Auth API tùy tiện.
- Share source FKRESTRICT giữ tombstone; purge phải xử lý referencing shares/cases trước. Recall/deleted body scrub không đợi physicalobjectcleanup.

### Index/replay
Không thấy exact duplicate index cần xóa. Composite unique(id,parent) dù id cóPK vẫn cần cho composite FK bảo đảm cùng parent, không bỏ vì coi là duplicate tên.
Catalog query đánh dấu24 FK chưa có full leading nonpartial index. Danh sách raw trong evidence; nhiều mục đã có partial IS NOT NULL hoặc index dẫn một phần khóa nên **không đồng nghĩa24 index thiếu**. Cần EXPLAIN deletion/cleanup ở data volume thật, ưu tiên profile avatar/cover/group-cover pointers và target-specific case queries nếu plan cho thấy scan. Không thêm24 indexes máy móc.
Baseline không reentrant: thử rerun localcopy nhận42P07 profiles exists và transaction rollback. Để triển khai dùng migrationledger+versionchecksum/transaction; retry ledger đã applied làno-op, failed migration rollback. Không IF NOT EXISTS che schema drift. Guard gốc cả2 SQL thực tế abortP0001 trước DDL.

## 3. Lỗi SQL/RLS/thiết kế đã phát hiện và xử lý
Không gặp syntax/type/create-order failure khi chạy originalDDL/RLS với required managed stubs; các phát hiện chính là semantic security/integrity và diagram/dependency.

| Mã | Mức | Trước / bằng chứng local | Sửa bản nháp | Kiểm chứng sau |
|---|---|---|---|---|
| A04-01 | Medium | Owner PATCH notification_preferences nhận unknown keys/nested nonboolean mà không qua APIvalidator |3 DB CHECK:4096byte/keycatalog/boolean values | unknown/nested/string/oversize fail23514; boolean allow |
| A04-02 | Medium | Một mediaasset có thể reuse2 stories, cleanup1 story ảnh hưởngstorykia | stories.media_asset_id UNIQUE; dictionary/ERD đồng bộ | repeatasset fails23505 |
| A04-03 | High defense-in-depth | Privileged importer có thể gắn recalledchatasset làmavatar; can_read_media chỉ thấy profilelink nên cho đọc lại | helper ràng purpose/owner từng profile/post/story/message branch; groupcover purposegroup | recalled-avatar confusion/wrongpurpose/wrongowner deny |
| A04-04 | High future-safety | Function mới tự nhận EXECUTE từPUBLIC. Thử REVOKE per-schema vẫn không gỡ global default, regression bắt lỗi | global ALTER DEFAULT PRIVILEGES REVOKE EXECUTE ON FUNCTIONS FROMPUBLIC cho migrationowner; existinghelper explicit grants giữ nguyên | newlycreatedpublic/privatefunctions not executable bởi authenticated; helperexplicitgrant vẫntrue |
| A04-05 | Low technical docs | ERD có4 cạnh tên FOREIGN giả khi parser đọc compositeFK2 lần; nullability vài selfreference/pointer sai | bỏ4 cạnh, nullableparent/reply, story asset1:1, last-messagehint0..1 |79 ERD edges =79 actualFK catalog |
| A04-06 | Medium rollout | Fullhelperbundle cần futuretables ngay khi CREATE, roadmap phânphase có thể dẫn copySQLsubsetfail | roadmap A0 dependency-closed scaffolding36 inert tables, APIs/grants A–E; subset chỉ nếu reviewhelpervariants riêng | fullfreshDDL/helpers compile; modularfuturevariants chưa viết |
| A04-07 | High open | RPCmutation/worker chưa cóbody, chỉ contracts; directDMLopen would bypass invariants | giữdeny, lập47 nhómRPC/API catalog và acceptancegates | chưa gọi bất kỳ mutationRPC thật nào |

A04-03 **không phải bằng chứng user Android hiện khai thác được**: Android đã không có raw UPDATE avatar/attachment grant. Đây là bypass dưới điều kiện sai liên kết từ trusted migration/operator hoặc RPCfuture lỗi, được siết ở readpolicy để fail-closed thêm.

PostgreSQL chỉ cho default privileges theo schema bổ sung quyền vào global defaults; per-schema revoke không gỡ PUBLIC EXECUTE toàn cục. Cách sửa đã được xác nhận bằng engine lẫn tài liệu. [ALTER DEFAULT PRIVILEGES](https://www.postgresql.org/docs/16/sql-alterdefaultprivileges.html)

## 4. Kết quả test thực sự
- **83 baseline assertions pass**: catalogue,FK/unique/check,anon/anonymous/unverified/incomplete/suspended, friend/nonfriend/private/group access,guesschatID/joinhistory,hash/endpointcolumns,role/counter/onboardDMLdeny,commenthidden/tombstone,Storyexpire,Storagepredicate/Realtime-topicstub,ownsettingspatch,block/removed/readhidden.
- **16 regression assertions pass**, tổng99: preferencesvalidation,Storyassetunique,public/privateglobaldefaultEXECUTE,helperexplicitgrant,mediawrongpurpose/owner/recalledavatar,allowedpendingupload/wrongownerdny,circularcascades.
- **3 real two-connection races pass**, quan sát backend wait_event_type=Lock rồi mới commitwriter1: reciprocal pendingrequest unique23505; same messageclientUUID unique23505;2 reactionwriters giữparentlock ->counter2/cardinality2.
-3 races chỉ là constraints/transactionpatterns củaSQLowner local, **không phải3 businessRPC đã triển khai**; không test app/outboxworker/AuthAPI.
- FreshfinalDB được recreate một lần saufixglobaldefault để verify cả order/defaultgrants từcleanstate;99+3 pass lại. Không tiếp tục broaden/repeat khi chưa có thay đổi/lỗi cần test.
- EXPLAIN(ANALYZE,BUFFERS,FORMATJSON) querymessagewindow dưới authenticated trên fixture nhỏ đã chạy: planning4.825ms/execution12.729ms/4 returnedrows. Chỉ kiểm queryplan và RLS ởfixture; không SLA/loadbenchmark. RLS helperfanout/hotparent locks vẫn cần 10k+synthetic/stagingvolume tests.

### Lệnh/chứng cứ tái lập
Runner:
```powershell
.\database_design\validation\Invoke-MeetoLocalSql.ps1 -PortableRoot <Temp\MeetoAudit04-UUID> -Database meeto_audit04_final -SqlFile <local-validation.sql>
.\database_design\validation\Invoke-MeetoConcurrency.ps1 -PortableRoot <Temp\MeetoAudit04-UUID>
```
Chi tiết setup/các scripts và thứ tự xem validation/README.md. Raw resultsJSON/catalog/plan lưu cùng thư mục. Các SQL test được guard database/localhost/tempdata_dir; không nằm supabase/migrations. Native packageSHA512 verified trước dùng, không cài projectdependency.

## 5. Rà soát RLS toàn bộ36 bảng
- RLS ON tất cả, no broad authenticatedwrites. Chỉ ownsettingscolumnpatch và saved/hiddenpreferenceCRUDtargetdesign được phép; A0 roadmap revoke preferencewrites tớiD nếu chưabậtfeature.
-21 helpers actor-bound(private schema),STABLE,SECURITYDEFINER,search_path='',schemaqualified. Functionowner cần BYPASSRLS hoặc tableowner-compatible; local owner superuser/testrolesNOBYPASS, **chưa xác minh actualSupabaseowner/roleinheritance/FORCERLS**.
- Scope: SystemAdmintriage != globalprivate-dataread; Moderatorassignedcaseonly; GroupAdminscopedgroup, không systemroles/case toàn hệ thống. Passed assigned/unassigned/adminprivate-deny local; provisioning/mutation notimplemented.
- account_status gate + onboarding + confirmed Auth thông qua mockcolumns;JWT verification/revocationservice chưa test. Same actor ownstatuslockedread đượcgiữ để báo tài khoản khóa.
- Block trước directchat/publicpersonalpost/media; memberremovedprivategroupdeny. Sharedgroup metadataexception và genericactivitytiming cần ownerchấp nhận; không hứa không biếtngườikia tồn tại.
- No views ởdraft. RPCfuturedefiner có thể bypassprivatefield khiJOIN nếu trảrawrows; cần safeprojection/authz test từngcatalogentry.
- FK/check/uniqueerrors có thể thành existenceoracle cho RPCdefiner/rawprivilegedwrite: map lỗi available/unavailable không làm lộ ID/PII; table DMLgrants không mở để tránh bug. UUIDkhóđoán không là authorization.
- Aggregates counts có thể gồm userbịblock; phải quyết định rawtotal hay viewerfilteredprojection. Không có backendhứa concealtiming tuyệt đối.
- Appendpoint không được exposeprivate schema; functiondefaultdeny chỉ theo migrationowner hiện tại, không tự áp ownerkhác; oldtable/column/inheritedgrants cần inventory riêng.
- Không FORCE RLS mù làm recursivehelper chạy lại policies. Local helperlookup không recursion trongcasesđãtest; không suy productionroles y hệt.

## 6. Kiểm chứng Storage/Realtime compatibility và rủi ro
**Đã kiểm SQL:** private-bucketcontext predicates,pendingintent/owner,readpurpose/owner,parentstate,sign/list operationdeny với mockoperation. **Chưa kiểm service:** actualStorageobjectownerassignment,upload/upsert/HEAD/GETInfo/range/signbatch/transforms/TUS/copy/move/delete,bytes/CDN/publicbucketflag; actualRealtimejoin/publish/tokenrefresh/TTLcache/private-channelsettings.

Tài liệu Supabase hiện có operation-aware helpers và owner_id từ verifiedJWT sub, nhưng phải kiểm tra instanceversion/routes thật; không suy localstub có đúng ABI/hành vi mọiroute. [Storage helpers](https://supabase.com/docs/guides/storage/schema/helper-functions), [Storage ownership](https://supabase.com/docs/guides/storage/security/ownership)

-4 privatebuckets profile/social/chat/group, noanonymousupload/no clientarbitrarydelete/upsert/signlongURL. Profiles avatar/cover làprivateauthenticatedload theo profileACL, không publicURLmặcđịnh.
- MIME/size/EXIF/quota/pendingcount/reservedbytes chỉ được hoàn thiện bằng bucketconfig +trusted finalize/worker; SQL CHECK không kiểm actualobjectbytes.
- Legacy public/signed URLs và cache không mất quyền chỉ vì DBfield/RLS đổi; cần retire oldobjects và CDNpolicy. Existingdownload không thu hồi; acceptancegate cho migration.
- Realtime auth cache có thể giữ subscriptionpermissions đến JWTrefresh. Vì vậy payload chỉgenericinvalidation, read RLSlại; commenttopic có timingsidechannel đãghi. [Realtime authorization](https://supabase.com/docs/guides/realtime/authorization)
- RealtimeWebSocket không thay backgroundpush. UnifiedPushnonFCM/OEM/no-pushdecision chưađượcduyệt; endpointcapability/SSRF/tokenrevocation/Doze là integrationwork, không backendSQLtestpass.

## 7. Đáp ứng8 nhóm sản phẩm
| Nhóm | Bảng/quan hệ | Query chính | RPC/API cần lập trình | Quyền/Storage/Realtime | Schemafuture và giới hạn |
|---|---|---|---|---|---|
| Account/profile | Authuser->profile/private/settings/status;blocks | selfprofile/publicsearchprefix | Auth chuẩn/bootstrap/onboard/update/delete | ownerPII,activeguard;profilemedia;Presencesauprivacyduyệt | Không cần password/sessionmirror; multi-episodepresence optional sau |
| Posts/feed | posts/media/reactions/comments/replies/save/hide | keysettime,id/friend/author/thread | CRUD/share/reaction/counter/comments | parentACL/audience;socialmedia;commentinvalidations |36 schema đã nhiều/mixedmedia/replies, chưa FTSglobal/rankingengine |
| Stories | storyassetunique/views/reactions/messages.reply_story | activeexpiry24h/ownviewers | create/delete/view/reaction/reply/cleanup | expiryparentACL,privatebytes;UItimer | Archiveoptional; không cần tableTTLriêng |
| Friends/follow | directedrequest +orderedfriendship;follow/block | pending/friends/followercursor | send/accept/resolve/unfriend/follow/block | own/scopedvisibility,noFriendsOnlyfromfollow | Gợi ý simplequery; advancedgraphranking khôngbắtbuộcbảngmới |
| Groups | group/memberrole/request +post.group | discovery/publicmetadata/memberfeed | join/approve/leave/ban/transfer/content | scopegroup,privatecover;removedinvalidate | Adminsuspendoperationalrecoverycầnduyệt; nếuhistorymembershipnhiềuepisodecầnextension |
| Chat | conv/member/messages/hidden/media | inboxper-viewer/seqcursor/scopedFTS | send/read/deliver/recall/hide/membermanage | activeboundary/block;privatechatmedia;userinvalidation | Existing schema supportsgroup; nếuretainoldhistoryepisodeskhácdefault cầnmembershipintervaltable |
| Notifications | recipientrows +devices/outbox/settings | unread/timecursor | writer/markread/register/revoke/dispatch | recipient-only,endpointsecret;userRealtime+providerpush | Provideradapterdecision,khôngdùngRealtimeđểgiảFCM |
| Moderation/admin | systemrole/cases/actions/audit/appeal | assignedcases/targets/scope | report/context/assign/action/suspend/role/appeal | rolebackendnarrow,noallprivatechat | Scopedappeal/statisticsworkflowpending; aggregates khôngcầnanalyticswarehousehiện tại |

Các feature không có UI không tự đòi bảng mới.36 bảng giữ tên/cấu trúc cốt lõi; chỉ sửa constraints/predicates có bằng chứng.

## 8. Danh sách RPC và kế hoạch dependency
Danh mục đầy đủ **47 nhóm operation**: [MEETO_RPC_IMPLEMENTATION_CATALOG.md](MEETO_RPC_IMPLEMENTATION_CATALOG.md), có inputs,outputs,authz,transaction/lock,error/retry. Trạng thái toàn bộmutationentries: **TO IMPLEMENT**, không tạo dummybodytrảsuccess.

Roadmap mới [MEETO_IMPLEMENTATION_ROADMAP.md](MEETO_IMPLEMENTATION_ROADMAP.md):
A foundation/Auth/profile +A0fulldependency scaffolding inert;
B posts/comments/reactions/friends/media;
C chat/inbox/media/read/Realtime;
D Stories/notifications/advanced;
E groups/moderation/admin.
Mỗi phase có tables/APIs/policies/tests/Androidimpact/DoD/rollback. Firebaseadapter bắt đầu thay từA khi đượcgiaoimplementation, cutover sauA+B+Cstagingpass+migrationrehearsal, xóa Firebase/legacyImgBB chỉ sauobservation/rollbackwindow vàinventoryđượcduyệt.

## 9. Vấn đề chưa thể xác minh / điều kiện deploy thật
1. Projectversion/extensions/managedschemafunctionABI/owners/grants/RLSexisting và realtimepublicsetting. Không kết nối project để đọc/thay đổi.
2.47 nhómRPC,workers/bootstrap/publisher chưa được lập trình vàtest; muốndeployusablebackend phải triển khai từngphase.
3. RealAuth(email/SMS/refresh/reset/deeplink),objectbytes/HTTP/CDN/push/WebSocket tests chưa làm.
4. Lastoperativegroupadmin khiaccountbịsuspend/delete: chốtstafftransfer/autoarchive/read-only, không tựnângquyềnGroupAdmin.
5. Publicguestpolicy,blocksharedgroup,chatnewjoin/rejoin/deleteaccountretention,Storyarchive,quotas/retention,nonFCMpushstrategy phảiđượcduyệt.
6. Actualdata/Authmigrationinventory/privateURLretirement,rollback/RPO/mappings/counterreconciliation chưa códữliệuthật/rehearsal.
7. RLSperformancelargedata/leaseworkerfailures/serviceavailability/rate-limit/SSRF cầntesting riêng.

**Gate triển khai:** reviewerduyệtDDL/RLS vàownerdefaults; versionedmigration từsnapshot; zero unresolvedHighsecurityinphase; actualSupabasestagingintegration+rolecontexts; RPCcase/race/idempotency pass; backups/recovery rehearsal; manualapprovalproduction. Không auto-removeguard hoặc chạySQLprojectthật trong nhiệm vụ04.

## 10. Bàn giao và kết luận
Tạo mới: audit này, catalogRPC,baocao_nhiemvu04.txt,validationharness/SQLfixtures/results/README. Cập nhật cócăn cứ: schema,RLS,dictionary,ERD,architecture,RLSdesign,StorageRealtime,migrationnotes,roadmap,designreview. **baocao.txt,Java/XML/Manifest/dependencies/local.properties/Firebasecode/supabase/storage.sql không sửa.**

Readiness: **NOT READY cho Supabase thật**; **PostgreSQL local baseline verified** với99assertions+3racepatterns, đủ để giao implementationstagingtheophase. Không nhầmcompile/read-policytests với completebusinessbackend hoặc quyền triển khai production.

