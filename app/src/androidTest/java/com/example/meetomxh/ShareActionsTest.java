package com.example.meetomxh;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.supabase.ShareActions;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

@RunWith(AndroidJUnit4.class)
public final class ShareActionsTest {
    @Test public void shareSheetShowsOnlyWiredDestinations() {
        try (ActivityScenario<LoginActivity> scenario=ActivityScenario.launch(LoginActivity.class)) {
            scenario.onActivity(activity->{Post post=new Post();post.setPostId("00000000-0000-0000-0000-000000000001");ShareActions.show(activity,post,false);});
            onView(withText("Chia sẻ bài viết")).check(matches(isDisplayed()));
            onView(withText("Chia sẻ lên bảng tin")).check(matches(isDisplayed()));
            onView(withText("Gửi qua Messenger")).check(matches(isDisplayed()));
            onView(withText("Chia sẻ vào nhóm")).check(matches(isDisplayed()));
            onView(withText("Sao chép liên kết")).check(matches(isDisplayed()));
        }
    }
}
