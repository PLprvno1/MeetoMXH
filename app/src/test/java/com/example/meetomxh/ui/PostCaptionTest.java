package com.example.meetomxh.ui;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PostCaptionTest {
    private JSONObject tag(String id,String name,String state)throws Exception{
        return new JSONObject().put("user_id",id).put("display_name",name).put("state",state);
    }
    @Test public void acceptedNamesAppearInCaptionWithExactLinkRanges()throws Exception{
        PostCaption caption=PostCaption.format("Đi chơi 🌸",new JSONArray().put(tag("a","Minh","accepted")).put(tag("b","Hoa","accepted")));
        assertEquals("Đi chơi 🌸\nCùng với Minh, Hoa",caption.text);
        assertEquals("Minh",caption.text.substring(caption.tags.get(0).start,caption.tags.get(0).end));
        assertEquals("b",caption.tags.get(1).userId);
    }
    @Test public void pendingTagsRemainVisibleToAuthorWithTheirStatus()throws Exception{
        assertEquals("Cùng với Minh (chờ duyệt)",PostCaption.format("",new JSONArray().put(tag("a","Minh","pending"))).text);
    }
    @Test public void rejectedRemovedAndDuplicateTagsAreExcluded()throws Exception{
        JSONArray tags=new JSONArray().put(tag("a","Minh","accepted")).put(tag("a","Minh","accepted"))
            .put(tag("b","Hoa","rejected")).put(tag("c","An","removed"));
        assertEquals("Ảnh mới\nCùng với Minh",PostCaption.format("Ảnh mới",tags).text);
    }
    @Test public void absentTagsPreserveTheBodyAndItsMentionOffsets(){
        assertEquals("Chào @Minh 🌸",PostCaption.format("Chào @Minh 🌸",null).text);
        assertEquals("",PostCaption.format(null,null).text);
    }
    @Test public void redactedProfilesHaveNoLinkAndMalformedRowsAreSkipped()throws Exception{
        JSONArray tags=new JSONArray().put(JSONObject.NULL).put(new JSONObject().put("state","accepted"));
        PostCaption caption=PostCaption.format(null,tags);
        assertEquals("Cùng với Người dùng Meeto",caption.text);assertNull(caption.tags.get(0).userId);
    }
}
