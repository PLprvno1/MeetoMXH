package com.example.meetomxh;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.ProfileClient;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.supabase.SessionManager;

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class CloudProfileTest {
    @Test public void cloudProfileUpdateUsesVersionAndRestoresFixture() throws Exception {
        Assert.assertTrue("signed-in synthetic account", !SessionManager.getInstance().uid().isEmpty());
        JSONObject original = ProfileClient.self();
        Assert.assertTrue("onboarding profile exists", original.optBoolean("onboarding_completed"));
        String previousBio = original.optString("bio", "");
        Object avatar = original.has("avatar_asset_id") && !original.isNull("avatar_asset_id")
                ? original.get("avatar_asset_id") : JSONObject.NULL;
        Object cover = original.has("cover_asset_id") && !original.isNull("cover_asset_id")
                ? original.get("cover_asset_id") : JSONObject.NULL;
        String testBio = "Task11 profile verification " + UUID.randomUUID();
        JSONObject changed = RpcClient.object("update_profile", Api.obj(
                "p_display_name", original.getString("display_name"), "p_bio", testBio,
                "p_avatar_asset_id", avatar, "p_cover_asset_id", cover,
                "p_expected_version", original.getLong("version")));
        try {
            Assert.assertEquals("versioned Cloud profile update", testBio, changed.getString("bio"));
            Assert.assertEquals("profile self refetch", testBio, ProfileClient.self().getString("bio"));
        } finally {
            JSONObject latest = ProfileClient.self();
            RpcClient.object("update_profile", Api.obj(
                    "p_display_name", latest.getString("display_name"), "p_bio", previousBio,
                    "p_avatar_asset_id", avatar, "p_cover_asset_id", cover,
                    "p_expected_version", latest.getLong("version")));
        }
    }
}
