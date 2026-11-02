package com.example.meetomxh;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.meetomxh.fragments.*;
import com.example.meetomxh.supabase.SessionManager;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class Task11CNavigationTest {
    private final SupabaseCutoverTest ui = new SupabaseCutoverTest();

    @Test public void topLevelNavigationBackRotationAndProfileCardStayConsistent() throws Exception {
        String actor = SessionManager.getInstance().uid();
        assertFalse(actor.isEmpty());
        ui.launch(MainActivity.class);
        onView(withId(R.id.nav_video)).perform(click());
        assertMain(FeedFragment.class, R.id.nav_feed);
        onView(withId(R.id.nav_notifications)).perform(click());
        assertMain(FeedFragment.class, R.id.nav_feed);
        onView(withId(R.id.nav_friends)).perform(click());
        assertMain(FriendsFragment.class, R.id.nav_friends);
        onView(withId(R.id.btnSearch)).perform(click());
        String peerName = com.example.meetomxh.supabase.RpcClient.object("get_profile",
                com.example.meetomxh.supabase.Api.obj("p_target", "32fde22e-5e5e-4e47-b2f6-ef09518ba1b9"))
                .getString("display_name");
        onView(withId(R.id.etSearchUsers)).perform(replaceText(peerName.substring(0, Math.min(3, peerName.length()))), closeSoftKeyboard());
        ui.waitFor("profile prefix search renders server results", () -> {
            Activity current = ui.activity();
            if (current == null) return false;
            final boolean[] visible = {false};
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                androidx.recyclerview.widget.RecyclerView list = current.findViewById(R.id.rvFriends);
                visible[0] = list != null && list.isShown() && list.getAdapter() != null && list.getAdapter().getItemCount() > 0;
            });
            return visible[0];
        });
        onView(withId(R.id.etSearchUsers)).perform(replaceText("Task11C_NoMatch_" + java.util.UUID.randomUUID().toString().substring(0, 8)), closeSoftKeyboard());
        ui.waitFor("search replaces old results with empty state", () -> {
            Activity current = ui.activity();
            if (current == null) return false;
            final boolean[] empty = {false};
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                android.widget.TextView status = current.findViewById(R.id.tvEmptyMessage);
                empty[0] = status != null && status.isShown() && status.getText().toString().contains("Task11C_NoMatch_");
            });
            return empty[0];
        });
        pressBack();
        assertMain(FriendsFragment.class, R.id.nav_friends);
        onView(withId(R.id.btnMessenger)).perform(click());
        assertMain(InboxFragment.class, R.id.nav_friends);
        pressBack();
        assertMain(FriendsFragment.class, R.id.nav_friends);
        onView(withId(R.id.nav_profile)).perform(click());
        onView(withId(R.id.cardProfile)).perform(click());
        ui.screen(ProfileActivity.class);
        pressBack();
        ui.screen(MainActivity.class);
        assertMain(ProfileFragment.class, R.id.nav_profile);
        Activity main = ui.activity();
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    main.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            ui.waitFor("landscape recreates main without identity change", () -> {
                Activity current = ui.activity();
                return current instanceof MainActivity && current.getResources().getConfiguration().orientation
                        == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            });
            assertEquals(actor, SessionManager.getInstance().uid());
            assertMain(ProfileFragment.class, R.id.nav_profile);
        } finally {
            Activity current = ui.activity();
            if (current != null) InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    current.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        }
        ui.waitFor("portrait restored after rotation probe", () -> ui.activity() instanceof MainActivity
                && ui.activity().getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT);
        int originalMode = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode();
        int originalNight = ui.activity().getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        try {
            onView(withId(R.id.switchDarkMode)).check(androidx.test.espresso.assertion.ViewAssertions.matches(
                    originalNight == android.content.res.Configuration.UI_MODE_NIGHT_YES
                            ? isChecked() : isNotChecked()));
            onView(withId(R.id.switchDarkMode)).perform(click());
            ui.waitFor("theme switch survives activity recreation", () -> ui.activity() instanceof MainActivity
                    && (ui.activity().getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK) != originalNight);
            assertEquals(actor, SessionManager.getInstance().uid());
            assertMain(ProfileFragment.class, R.id.nav_profile);
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(originalMode));
        }
    }

    private void assertMain(Class<?> fragmentType, int selected) throws Exception {
        ui.waitFor("main navigation matches current screen", () -> {
            Activity current = ui.activity();
            if (!(current instanceof MainActivity)) return false;
            final boolean[] correct = {false};
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                MainActivity main = (MainActivity) current;
                BottomNavigationView nav = main.findViewById(R.id.bottom_navigation);
                correct[0] = fragmentType.isInstance(main.getSupportFragmentManager()
                        .findFragmentById(R.id.fragment_container)) && nav.getSelectedItemId() == selected;
            });
            return correct[0];
        });
    }
}
