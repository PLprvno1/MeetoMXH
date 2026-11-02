package com.example.meetomxh.supabase;
public final class ErrorMapper {
 public static final class HttpError extends java.io.IOException {public final int status;public final String code;public HttpError(int status,String code){super("HTTP "+status);this.status=status;this.code=code;}}
 public static String message(Exception error){
  if(error instanceof HttpError){HttpError e=(HttpError)error;
   if(e.status==409)return "Dữ liệu đã thay đổi. Hãy tải lại trước khi thử tiếp.";
   if(e.status==401)return "Phiên đăng nhập không còn hợp lệ. Vui lòng đăng nhập lại.";
   if("email_not_confirmed".equals(e.code))return "Vui lòng xác minh email trước khi đăng nhập.";
   if(e.status==403)return "Tài khoản hoặc quyền truy cập hiện không khả dụng.";
   if(e.status==404)return "Nội dung không tồn tại hoặc bạn không còn quyền xem.";
   if(e.status==429)return "Đã đạt giới hạn. Vui lòng thử lại sau.";
   if(e.status==410)return "Phiên tải lên đã hết hạn. Hãy chọn lại tệp.";
   return "Yêu cầu không hợp lệ. Kiểm tra dữ liệu và thử lại.";
  }
  if(error instanceof java.io.IOException)return "Không kết nối được máy chủ. Kiểm tra mạng rồi thử lại.";
  return "Không hoàn thành thao tác. Vui lòng thử lại.";
 }
}
