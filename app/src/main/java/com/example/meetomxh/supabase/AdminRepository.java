package com.example.meetomxh.supabase;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.json.JSONObject;

/** Screen-owned jobs, typed errors before localization, and persistent per-intent request identity. */
public final class AdminRepository {
    public enum ErrorKind { SESSION, PERMISSION, CONFLICT, UNAVAILABLE, RETIRED, VALIDATION, NETWORK, SERVER, CONTRACT }
    public static final class AdminError {
        public final ErrorKind kind;
        public final String message;
        public final int status;
        public final String code;
        AdminError(Exception failure) {
            if(failure instanceof ErrorMapper.HttpError) {
                status=((ErrorMapper.HttpError)failure).status;code=((ErrorMapper.HttpError)failure).code;
                kind="PGRST202".equals(code)?ErrorKind.CONTRACT:status==401?ErrorKind.SESSION:status==403?ErrorKind.PERMISSION:status==404?ErrorKind.UNAVAILABLE:
                    status==409?ErrorKind.CONFLICT:status==410?ErrorKind.RETIRED:(status==400||status==422)?ErrorKind.VALIDATION:ErrorKind.SERVER;
            } else {
                status=0;code="";
                kind=failure instanceof org.json.JSONException||failure instanceof ClassCastException?ErrorKind.CONTRACT:
                    failure instanceof java.io.IOException?ErrorKind.NETWORK:ErrorKind.SERVER;
            }
            switch(kind) {
                case SESSION: message="Phiên đăng nhập đã hết hạn."; break;
                case PERMISSION: message="Không còn quyền thực hiện thao tác quản trị này."; break;
                case CONFLICT: message="Trạng thái đã thay đổi hoặc request không khớp. Tải lại để kiểm tra."; break;
                case UNAVAILABLE: message="Báo cáo hoặc nội dung không còn khả dụng."; break;
                case RETIRED: message="Chức năng này đã ngừng hỗ trợ."; break;
                case VALIDATION: message="Kiểm tra loại thao tác và lý do (1–1000 ký tự)."; break;
                case NETWORK: message="Chưa xác định kết quả do lỗi mạng. Thử lại cùng thao tác để kiểm tra receipt."; break;
                case CONTRACT: message="Hợp đồng Admin không hợp lệ; cần đối chiếu migration Cloud."; break;
                default: message="Không thực hiện được yêu cầu quản trị. Kiểm tra kết nối và migration Cloud.";
            }
        }
    }
    private static final class Result<T> {
        T value; AdminError error;
    }
    private final List<Subscription> jobs=new ArrayList<>();
    private int generation;
    public AdminError lastError;
    public <T> void run(Callable<T> task,Api.Callback<T> callback) {
        final int expected=generation;
        jobs.add(Api.run(()->{
            Result<T> result=new Result<>();
            try { result.value=task.call(); } catch(Exception failure) { result.error=new AdminError(failure); }
            return result;
        },new Api.Callback<Result<T>>() {
            public void success(Result<T> result) {
                if(expected!=generation)return;
                lastError=result.error;
                if(result.error==null)callback.success(result.value); else callback.error(result.error.message);
            }
            public void error(String ignored) {
                if(expected!=generation)return;
                lastError=new AdminError(new java.io.IOException()); callback.error(lastError.message);
            }
        }));
    }
    public void cancelAll() {
        generation++; for(Subscription job:jobs)job.remove(); jobs.clear();
    }
    private static String fingerprint(JSONObject args) {
        List<String> keys=new ArrayList<>();
        java.util.Iterator<String> iterator=args.keys();
        while(iterator.hasNext()){String key=iterator.next();if(!"p_request_id".equals(key))keys.add(key);}
        java.util.Collections.sort(keys);
        org.json.JSONArray pairs=new org.json.JSONArray();
        for(String key:keys){pairs.put(key);pairs.put(args.opt(key));}
        return pairs.toString();
    }
    public JSONObject receiptFor(String rpc,JSONObject args) throws Exception {
        String id=RequestIds.get(rpc,fingerprint(args));
        Object value=RpcClient.call("admin_action_receipt",Api.obj("p_request_id",id));
        if(!(value instanceof JSONObject))return null;
        AdminModels.Receipt receipt=new AdminModels.Receipt((JSONObject)value);
        if(!id.equals(receipt.requestId))throw new org.json.JSONException("Receipt request mismatch");
        return receipt.value;
    }
    public void completeIntent(String rpc,JSONObject args){RequestIds.complete(rpc,fingerprint(args));}
    /** Flat RPC arguments are canonicalized by key; completion ends this intent. */
    public void submit(String rpc,JSONObject args,Api.Callback<JSONObject> callback) {
        final String identity=fingerprint(args);
        final String requestId=RequestIds.get(rpc,identity);
        run(()->{
            JSONObject existing=receiptFor(rpc,args);
            if(existing!=null)return existing;
            args.put("p_request_id",requestId);
            JSONObject receipt=RpcClient.object(rpc,args);
            AdminModels.Receipt parsed=new AdminModels.Receipt(receipt);
            if(!requestId.equals(parsed.requestId))throw new org.json.JSONException("Receipt request mismatch");
            return parsed.value;
        },new Api.Callback<JSONObject>() {
            public void success(JSONObject result) { RequestIds.complete(rpc,identity); callback.success(result); }
            public void error(String message) { callback.error(message); }
        });
    }
}
