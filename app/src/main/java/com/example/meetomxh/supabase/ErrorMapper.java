package com.example.meetomxh.supabase;
public final class ErrorMapper {
 public static final class HttpError extends java.io.IOException {public final int status;public final String code;public final String serverMessage;public HttpError(int status,String code){this(status,code,"");}public HttpError(int status,String code,String serverMessage){super("HTTP "+status);this.status=status;this.code=code;this.serverMessage=serverMessage==null?"":serverMessage;}}
 public static String message(Exception error){
  if(error instanceof HttpError){HttpError e=(HttpError)error;
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
   if(e.status==429)return "Đã đạt giới hạn. Vui lòng thử lại sau.";
   if("invalid_credentials".equals(e.code))return "Email hoặc mật khẩu không chính xác.";
   if("over_email_send_rate_limit".equals(e.code)||"email_rate_limit_exceeded".equals(e.code))return "Supabase đang giới hạn email khôi phục. Hãy thử lại sau.";
   if(e.status==410)return "Phiên tải lên đã hết hạn. Hãy chọn lại tệp.";
   return "Yêu cầu không hợp lệ. Kiểm tra dữ liệu và thử lại.";
  }
  if(error instanceof IllegalStateException)return error.getMessage();
  if(error instanceof java.io.IOException)return "Không kết nối được máy chủ. Kiểm tra mạng rồi thử lại.";
  return "Không hoàn thành thao tác. Vui lòng thử lại.";
 }
}
