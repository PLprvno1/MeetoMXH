package com.example.meetomxh;

import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.meetomxh.adapters.FeedAdapter;
import com.example.meetomxh.repositories.FeedRepository;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.swipeDown;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

@RunWith(AndroidJUnit4.class)
public final class FeedLayoutRecoveryTest {
    @Test public void feedContainerAndComposerRemainAvailable() {
        ContextThemeWrapper themed = new ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), R.style.Theme_MeetoMXH);
        View root = LayoutInflater.from(themed).inflate(R.layout.fragment_feed, null, false);

        SwipeRefreshLayout swipe = root.findViewById(R.id.swipeFeed);
        RecyclerView feed = root.findViewById(R.id.rvFeed);
        assertNotNull(swipe);
        assertNotNull(feed);
        assertEquals(View.VISIBLE, swipe.getVisibility());
        assertTrue("feed RecyclerView must be hosted by SwipeRefreshLayout", feed.getParent() == swipe);

        FeedAdapter adapter = new FeedAdapter(null);
        adapter.setPosts(java.util.Collections.emptyList());
        assertEquals("composer and stories remain even when no posts load", 2, adapter.getItemCount());
        assertEquals(FeedAdapter.TYPE_CREATE_POST, adapter.getItemViewType(0));
        assertEquals(FeedAdapter.TYPE_STORIES, adapter.getItemViewType(1));

        View composer = LayoutInflater.from(themed).inflate(R.layout.item_create_post, null, false);
        assertNotNull(composer.findViewById(R.id.tvWhatOnYourMind));
    }

    @Test public void attachedFeedShowsComposerAndRetryAfterRefreshFailure() {
        try (ActivityScenario<LoginActivity> scenario = ActivityScenario.launch(LoginActivity.class)) {
            scenario.onActivity(activity -> {
                FrameLayout host = new FrameLayout(activity);
                host.setId(R.id.fragment_container);
                activity.setContentView(host);
                activity.getSupportFragmentManager().beginTransaction()
                        .replace(R.id.fragment_container, new com.example.meetomxh.fragments.FeedFragment())
                        .commitNow();
            });

            onView(withId(R.id.swipeFeed)).check(matches(isDisplayed()));
            onView(withId(R.id.rvFeed)).check(matches(isDisplayed()));
            onView(withId(R.id.tvWhatOnYourMind)).check(matches(isDisplayed()));
            onView(withId(R.id.rvFeed)).perform(swipeDown());
            scenario.onActivity(activity -> FeedRepository.getInstance().refresh());

            AtomicBoolean retryVisible = new AtomicBoolean();
            long deadline = android.os.SystemClock.uptimeMillis() + 5000;
            while (android.os.SystemClock.uptimeMillis() < deadline && !retryVisible.get()) {
                scenario.onActivity(activity -> {
                    TextView status = activity.findViewById(R.id.feedStatus);
                    retryVisible.set(status != null && status.getVisibility() == View.VISIBLE
                            && status.getText().toString().contains("thử lại"));
                });
                if (!retryVisible.get()) android.os.SystemClock.sleep(100);
            }
            assertTrue("failed refresh must show retry state without hiding the composer", retryVisible.get());
            onView(withId(R.id.tvWhatOnYourMind)).check(matches(isDisplayed()));
        }
    }

}
