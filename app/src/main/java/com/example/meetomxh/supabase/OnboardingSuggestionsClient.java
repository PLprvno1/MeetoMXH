package com.example.meetomxh.supabase;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Bounded basic profile discovery, independent of the caller's existing Follow graph. */
public final class OnboardingSuggestionsClient {
    public static final int PAGE_SIZE=20;
    public static final class Person {
        public final String id,name,username,avatar,preset;
        public boolean following,requested;
        Person(JSONObject row)throws Exception{
            id=row.getString("profile_id");
            if(!UUID.fromString(id).toString().equalsIgnoreCase(id))throw new IllegalStateException("Máy chủ trả mã tài khoản không hợp lệ.");
            String display=Dto.nullable(row,"display_name");name=display==null||display.trim().isEmpty()?"Người dùng":display;
            String handle=Dto.nullable(row,"username");username=handle==null?"":handle;
            avatar=Dto.asset(Dto.nullable(row,"avatar_asset_id"));preset=row.optString("avatar_preset","neutral");
            following=row.getBoolean("following");requested=row.getBoolean("requested");
        }
    }
    public static final class Page {
        public final List<Person> people=new ArrayList<>();
        public final boolean hasMore;
        public final String afterTime,afterId;
        Page(JSONArray rows)throws Exception{
            if(rows.length()>PAGE_SIZE)throw new IllegalStateException("Máy chủ trả trang gợi ý vượt giới hạn.");
            for(int i=0;i<rows.length();i++)people.add(new Person(rows.getJSONObject(i)));
            hasMore=rows.length()==PAGE_SIZE;
            JSONObject last=rows.optJSONObject(rows.length()-1);
            afterTime=last==null?null:last.getString("created_at");afterId=last==null?null:last.getString("profile_id");
        }
    }
    public static Page page(String time,String id)throws Exception{
        try{
            // RpcClient keeps Task 36 guard; never fall back to raw profiles SELECT.
            JSONArray rows=(JSONArray)RpcClient.call("get_onboarding_suggestions",Api.obj("p_after_time",time,"p_after_id",id,"p_limit",PAGE_SIZE));
            Page page=new Page(rows);
            if(page.hasMore&&(page.afterTime==null||page.afterTime.isEmpty()||"null".equals(page.afterTime)
                    ||(page.afterTime.equals(time)&&page.afterId.equals(id))))
                throw new IllegalStateException("Máy chủ trả mốc trang gợi ý không hợp lệ. Hãy thử lại sau khi cập nhật.");
            return page;
        }catch(ErrorMapper.HttpError e){
            if(e.status==404||"PGRST202".equals(e.code)||"42883".equals(e.code))
                throw new IllegalStateException("Máy chủ chưa có chức năng gợi ý mới. Chủ dự án cần triển khai bản cập nhật gợi ý sau đăng ký.");
            if("25006".equals(e.code))
                throw new IllegalStateException("Tính năng gợi ý chưa tương thích với máy chủ hiện tại. Chủ dự án cần cập nhật máy chủ.");
            if(e.status==403&&("active_onboarded_account_required".equals(e.serverMessage)||"suggestions_account_unavailable".equals(e.serverMessage)))
                throw new IllegalStateException("Tài khoản chưa đủ điều kiện xem gợi ý. Cần xác minh email, hoàn tất hồ sơ và có trạng thái hoạt động.");
            throw e;
        }
    }
    public static JSONObject follow(String id)throws Exception{
        JSONObject result=RpcClient.object("set_follow",Api.obj("p_target",id,"p_follow",true));
        if(!id.equals(result.optString("target_id")))throw new IllegalStateException("Máy chủ chưa xác nhận đúng tài khoản được theo dõi.");
        result.getBoolean("following");result.getBoolean("requested");return result;
    }
    private OnboardingSuggestionsClient(){}
}
