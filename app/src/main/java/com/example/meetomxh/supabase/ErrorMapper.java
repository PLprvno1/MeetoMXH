package com.example.meetomxh.supabase;
public final class ErrorMapper {
 public static HttpError httpError(int status,String body){
  String code="",message="";try{org.json.JSONObject o=new org.json.JSONObject(body);code=o.optString("error_code",o.optString("code",o.optString("error",o.optString("statusCode",""))));message=o.optString("message","");}catch(org.json.JSONException ignored){}
  if("PT409".equals(code)&&"verification_pending".equals(message))code="verification_pending";
  return new HttpError(status,code,message);
 }
 public static boolean quota(HttpError e){String text=(e.code+" "+e.serverMessage).toLowerCase(java.util.Locale.ROOT);
  return text.contains("media_quota")||text.contains("quota")||e.status==402||e.status==507;}
 public static void diagnostic(String stage,Exception error){
  String detail="type="+error.getClass().getSimpleName();
  if(error instanceof HttpError){HttpError h=(HttpError)error;detail="http="+h.status+" code="+safeDiagnosticCode(h.code)+" reason="+safeDiagnosticCode(h.serverMessage);}
  android.util.Log.w("MeetoPublish", "stage="+stage+" "+detail);
 }
 private static String safeDiagnosticCode(String code){
  // Only known machine codes, never an arbitrary server body/message, URL or token.
  String value=code==null?"":code;
  if(value.matches("PT[0-9]{3}|PGRST[0-9]{3}"))return value;
  switch(value.toLowerCase(java.util.Locale.ROOT)){
   case "media_quota":case "overquota":case "storagequotaexceeded":case "entitytoolarge":case "payloadtoolarge":
   case "ratelimitexceeded":case "too_many_requests":case "toomanyrequests":case "accessdenied":case "unauthorized":case "invalidjwt":
   case "resourcelocked":case "resourcealreadyexists":case "duplicate":case "conflict":case "nosuchbucket":
   case "intent_closed":case "upload_object_missing":case "upload_metadata_invalid":case "verification_pending":
   case "legacy_upload_intent":case "idempotency_conflict":case "invalid_reel_video":case "invalid_reel_cover":
   case "invalid_reel":case "version_conflict":return value.toLowerCase(java.util.Locale.ROOT);
   default:return value.isEmpty()?"none":"other";
  }
 }
 public static final class HttpError extends java.io.IOException {public final int status;public final String code;public final String serverMessage;public HttpError(int status,String code){this(status,code,"");}public HttpError(int status,String code,String serverMessage){super("HTTP "+status);this.status=status;this.code=code;this.serverMessage=serverMessage==null?"":serverMessage;}}
 public static String message(Exception error){
  if(error instanceof HttpError){HttpError e=(HttpError)error;
   String reason=(e.code+" "+e.serverMessage).toLowerCase(java.util.Locale.ROOT);
   if(reason.contains("media_quota"))return "Máy chủ vẫn đang áp dụng quota media theo tài khoản. Owner cần triển khai bản cập nhật bỏ quota; bản nháp vẫn được giữ.";
   if(quota(e))return "Dự án Storage đã chạm quota hoặc giới hạn gói dịch vụ. Owner cần kiểm tra Usage/Storage/Billing trên Supabase Dashboard trước khi thử lại.";
   if(e.status==413||reason.contains("entitytoolarge")||reason.contains("entity too large")||reason.contains("payloadtoolarge")||reason.contains("payload too large")||reason.contains("maximum allowed size"))return "File vượt giới hạn dung lượng của Storage/bucket. Hãy cắt hoặc nén video; owner cần kiểm tra giới hạn bucket thực tế.";
   if(e.status==429||reason.contains("ratelimit")||reason.contains("too many requests")||reason.contains("toomanyrequests"))return "Máy chủ đang giới hạn tần suất yêu cầu"+(e.status==429?" (HTTP 429)":"")+". Hãy chờ rồi mới nhấn Thử lại.";
   if(reason.contains("intent_closed")||e.status==410)return "Phiên upload đã hết hạn hoặc đóng. Bản nháp vẫn được giữ; cần tạo lại phiên upload hợp lệ, không tự lặp retry.";
   if(reason.contains("verification_pending"))return "File đã tải nhưng media verifier chưa xác nhận. Giữ bản nháp và thử lại sau khi xử lý media hoàn tất.";
   if(reason.contains("upload_metadata_invalid"))return "Media verifier từ chối metadata của file: cần kiểm tra MIME, size, bucket, object và quyền sở hữu. Chưa đăng Reel.";
   if(reason.contains("upload_object_missing"))return "Storage chưa xác nhận object upload. Giữ bản nháp và thử lại bước tải/xác nhận media.";
   if(reason.contains("resourcealreadyexists")||reason.contains("duplicate")||reason.contains("idempotency_conflict"))return "Object hoặc request ID đã tồn tại với dữ liệu khác. Giữ bản nháp; cần đối chiếu phiên upload trước khi thử lại.";
   if("PGRST202".equals(e.code)||"42883".equals(e.code))return "Cloud thiếu RPC/migration cần cho chức năng này. Owner cần triển khai migration tương ứng trước khi thử lại.";
   if(e.status==401||reason.contains("invalidjwt")||reason.contains("unauthorized"))return "Phiên đăng nhập không hợp lệ hoặc đã hết hạn. Hãy đăng nhập lại; bản nháp vẫn được giữ.";
   if(e.status==403||reason.contains("accessdenied")||reason.contains("access denied"))return "Không có quyền upload/đọc media hoặc bucket policy từ chối. Owner cần đối chiếu Auth và Storage/RLS; không đổi bucket sang Public.";
   if("private_account_public_audience".equals(e.serverMessage))return "Tài khoản riêng tư không hỗ trợ quyền Công khai. Hãy chọn Người theo dõi hoặc Chỉ mình tôi.";
   if("reel_storage_unavailable".equals(e.serverMessage))return "Storage hiện chưa cho phép tải video Reels. Chưa tải video lên; vui lòng thử lại sau.";
   if("invalid_reel_audience".equals(e.serverMessage))return "Hãy chọn quyền Công khai, Người theo dõi hoặc Chỉ mình tôi cho Reel.";
   if("invalid_reel_video".equals(e.serverMessage))return "Video chưa đáp ứng kiểm tra định dạng, dung lượng, quyền sở hữu hoặc trạng thái Storage của Reels.";
   if("invalid_reel".equals(e.serverMessage))return "Reel cần video tối đa 60 giây, caption tối đa 2.000 ký tự và quyền riêng tư hợp lệ.";
   if("username_taken".equals(e.serverMessage))return "Tên người dùng đã được sử dụng.";
   if("invalid_username".equals(e.serverMessage))return "Tên người dùng cần 3–30 ký tự chữ thường, số, dấu chấm hoặc gạch dưới.";
   if("approved_follow_required".equals(e.serverMessage))return "Hãy theo dõi và chờ chấp nhận để nhắn tin.";
   if("message_request_required".equals(e.serverMessage))return "Người nhận chưa chấp nhận nhắn tin. Hãy gửi lời giới thiệu từ hồ sơ.";
   if("message_request_already_sent".equals(e.serverMessage))return "Bạn đã gửi một yêu cầu. Không thể gửi thêm khi chưa được chấp nhận.";
   if("group_owner_protected".equals(e.serverMessage))return "Không thể loại hoặc giáng quyền chủ nhóm.";
   if("contact_blocked".equals(e.serverMessage))return "Không thể gửi tin nhắn vì có thành viên đã chặn liên hệ.";
   if("system_admin_required".equals(e.serverMessage))return "system_admin_required";
   if("legacy_upload_intent".equals(e.code))return "This upload reservation uses the legacy media verifier. Select the file again to start a direct upload.";
   if("upload_object_missing".equals(e.code))return "Storage has not confirmed this file yet. Retry the upload.";
   if("verification_pending".equals(e.code))return "The voice file is still being checked. Keep this preview and retry shortly.";
   if("upload_metadata_invalid".equals(e.code))return "The uploaded file did not meet the Storage type, size, or ownership checks.";
   if("intent_closed".equals(e.code))return "This upload reservation expired. Select the file again.";
   if("PGRST202".equals(e.code))return "Máy chủ chưa hỗ trợ chức năng này. Vui lòng thử lại sau khi nâng cấp.";
   if(e.status==409)return "Dữ liệu đã thay đổi. Hãy tải lại trước khi thử tiếp.";
   if(e.status==401)return "Phiên đăng nhập không còn hợp lệ. Vui lòng đăng nhập lại.";
   if("email_not_confirmed".equals(e.code))return "Vui lòng xác minh email trước khi đăng nhập.";
   if(e.status==403)return "Tài khoản hoặc quyền truy cập hiện không khả dụng.";
   if(e.status==404)return "Nội dung không tồn tại hoặc bạn không còn quyền xem.";
   if("invalid_credentials".equals(e.code))return "Email hoặc mật khẩu không chính xác.";
   if("over_email_send_rate_limit".equals(e.code)||"email_rate_limit_exceeded".equals(e.code))return "Supabase đang giới hạn email khôi phục. Hãy thử lại sau.";
   if(e.status==410)return "Phiên tải lên đã hết hạn. Hãy chọn lại tệp.";
   if(e.status>=500)return "Máy chủ tạm thời không xử lý được yêu cầu. Giữ bản nháp và nhấn Thử lại sau; không gửi liên tục.";
   return "Yêu cầu không hợp lệ. Kiểm tra dữ liệu và thử lại.";
  }
  if(error instanceof java.net.SocketTimeoutException||error instanceof java.io.InterruptedIOException)return "Kết nối bị timeout. Bản nháp vẫn được giữ; nhấn Thử lại để tiếp tục bước chưa hoàn tất.";
  if(error instanceof IllegalStateException)return error.getMessage();
  if(error instanceof java.io.IOException)return "Không kết nối được máy chủ. Kiểm tra mạng rồi thử lại.";
  return "Không hoàn thành thao tác. Vui lòng thử lại.";
 }
}
