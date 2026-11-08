package com.example.meetomxh.ui;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Display-only tags; never rewrites the stored body or mention offsets. */
public final class PostCaption {
    public static final class Tag {
        public final String userId;
        public final int start,end;
        private Tag(String id,int start,int end){userId=id;this.start=start;this.end=end;}
    }
    public final String text;
    public final List<Tag> tags;
    private PostCaption(String text,List<Tag> tags){this.text=text;this.tags=tags;}
    public static PostCaption format(String body,JSONArray rawTags){
        StringBuilder text=new StringBuilder(body==null?"":body);
        List<Tag> tags=new ArrayList<>();Set<String> seen=new HashSet<>();
        if(rawTags!=null)for(int i=0;i<rawTags.length();i++){
            JSONObject row=rawTags.optJSONObject(i);if(row==null)continue;
            String state=row.optString("state","accepted");
            if(!"accepted".equals(state)&&!"pending".equals(state))continue;
            String id=row.isNull("user_id")?null:row.optString("user_id",null);
            if(id!=null&&(id.isEmpty()||"null".equals(id)))id=null;
            if(id!=null&&!seen.add(id))continue;
            String name=row.isNull("display_name")?"":row.optString("display_name","").trim();
            if(name.isEmpty())name="Người dùng Meeto";
            if(tags.isEmpty()){if(text.length()>0)text.append('\n');text.append("Cùng với ");}
            else text.append(", ");
            int start=text.length();text.append(name);tags.add(new Tag(id,start,text.length()));
            // The server returns pending tags only to the author; retain that distinction.
            if("pending".equals(state))text.append(" (chờ duyệt)");
        }
        return new PostCaption(text.toString(),tags);
    }
}
