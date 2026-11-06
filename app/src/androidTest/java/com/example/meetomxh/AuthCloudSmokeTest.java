package com.example.meetomxh;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.meetomxh.fragments.FeedFragment;
import com.example.meetomxh.fragments.SearchFragment;
import com.example.meetomxh.fragments.InboxFragment;
import com.example.meetomxh.fragments.ProfileFragment;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.ErrorMapper;
import com.example.meetomxh.supabase.ProfileClient;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.SessionUser;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Cloud Auth smoke. Test credentials arrive in memory over a temporary forwarded loopback socket. */
@RunWith(AndroidJUnit4.class)
public class AuthCloudSmokeTest {
    private final SupabaseCutoverTest ui = new SupabaseCutoverTest();
    private JSONObject credentials;
    private String recoveryResult = "NOT_RUN";

    @Test
    public void seedAccountAForForceStopSessionRestore() throws Exception {
        try {
            credentials = receiveCredentials();
            logoutIfSignedIn();
            login(0);
            verifyIdentity(credentials.getJSONArray("emails").getString(0));
            verifyMainNavigationAndTheme();
            ui.launch(MainActivity.class);
            assertFalse("account A session remains persisted for force-stop probe",
                    SessionManager.getInstance().uid().isEmpty());
        } finally {
            credentials = null;
        }
    }

    @Test
    public void twoAccountLoginWrongPasswordSessionUiAndSingleRecoveryRequest() throws Exception {
        androidx.test.espresso.Espresso.setFailureHandler((error, matcher) -> {
            throw new AssertionError("UI action failed (details suppressed): "
                    + error.getClass().getSimpleName());
        });

        try {
            credentials = receiveCredentials();
            JSONArray emails = credentials.getJSONArray("emails");
            String password = credentials.getString("password");
            assertEquals("two permitted test accounts supplied", 2, emails.length());

            logoutIfSignedIn();
            onView(withId(R.id.etEmail)).perform(replaceText(emails.getString(0)), closeSoftKeyboard());
            onView(withId(R.id.etPassword)).perform(replaceText(password + "-wrong"), closeSoftKeyboard());
            onView(withId(R.id.btnLogin)).perform(click());
            ui.waitFor("wrong password releases retry", () -> {
                try {
                    onView(withId(R.id.btnLogin)).check(matches(isEnabled()));
                    return true;
                } catch (AssertionError waiting) {
                    return false;
                }
            });
            assertTrue("wrong password leaves app anonymous", SessionManager.getInstance().uid().isEmpty());
            assertEquals("invalid credential message mapping",
                    "Email hoặc mật khẩu không chính xác.",
                    ErrorMapper.message(new ErrorMapper.HttpError(400, "invalid_credentials")));

            login(0);
            verifyIdentity(emails.getString(0));
            verifyMainNavigationAndTheme();
            logoutIfSignedIn();

            login(1);
            verifyIdentity(emails.getString(1));
            verifyMainNavigationAndTheme();
            logoutIfSignedIn();

            login(0);
            verifyIdentity(emails.getString(0));
            verifyMainNavigationAndTheme();

            // One recovery request only. API acceptance is reported separately from mail delivery.
            logoutIfSignedIn();
            onView(withId(R.id.tvForgotPassword)).perform(click());
            ui.screen(ForgotPasswordActivity.class);
            onView(withHint("Email")).perform(replaceText(emails.getString(0)), closeSoftKeyboard());
            onView(withTextSafe("Gửi email khôi phục")).perform(click());
            ui.waitFor("recovery API returns a form status", () -> {
                Activity current = ui.activity();
                if (!(current instanceof ForgotPasswordActivity)) return false;
                final boolean[] ready = {false};
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    TextView status = current.findViewById(R.id.formStatus);
                    ready[0] = status != null && status.getVisibility() == android.view.View.VISIBLE
                            && status.getText().length() > 0;
                });
                return ready[0];
            });
            Activity recovery = ui.activity();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                TextView status = recovery.findViewById(R.id.formStatus);
                String text = status == null ? "" : status.getText().toString();
                if (text.startsWith("Nếu email thuộc")) recoveryResult = "API_ACCEPTED";
                else if (text.contains("giới hạn")) recoveryResult = "RATE_LIMITED";
                else recoveryResult = "API_ERROR";
            });
            Bundle recoveryStatus = new Bundle();
            recoveryStatus.putString("stream", "Recovery result=" + recoveryResult
                    + "; email delivery and callback are not inferred from API response\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(0, recoveryStatus);
        } finally {
            credentials = null;
            Bundle result = new Bundle();
            result.putString("stream", "Auth cloud smoke finished; recovery=" + recoveryResult + "\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(0, result);
        }
    }

    private JSONObject receiveCredentials() throws Exception {
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 38712));
            server.setSoTimeout(120000);
            Bundle ready = new Bundle();
            ready.putString("stream", "Secure in-memory credential receiver ready\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(0, ready);
            try (Socket client = server.accept()) {
                client.setSoTimeout(10000);
                return new JSONObject(new BufferedReader(new InputStreamReader(
                        client.getInputStream(), StandardCharsets.UTF_8)).readLine());
            }
        }
    }

    private void login(int account) throws Exception {
        logoutIfSignedIn();
        ui.launch(LoginActivity.class);
        onView(withId(R.id.etEmail)).perform(replaceText(
                credentials.getJSONArray("emails").getString(account)), closeSoftKeyboard());
        onView(withId(R.id.etPassword)).perform(replaceText(
                credentials.getString("password")), closeSoftKeyboard());
        onView(withId(R.id.btnLogin)).perform(click());
        ui.screen(MainActivity.class);
        assertFalse("login produced a real authenticated identity", SessionManager.getInstance().uid().isEmpty());
    }

    private void logoutIfSignedIn() throws Exception {
        if (SessionManager.getInstance().uid().isEmpty()) {
            ui.launch(LoginActivity.class);
            return;
        }
        ui.launch(MainActivity.class);
        onView(withId(R.id.nav_profile)).perform(click());
        onView(withId(R.id.btnLogout)).perform(scrollTo(), click());
        ui.screen(LoginActivity.class);
        assertTrue("logout clears local authenticated identity", SessionManager.getInstance().uid().isEmpty());
    }

    private void verifyIdentity(String expectedEmail) throws Exception {
        SessionUser user = SessionManager.getInstance().getCurrentUser();
        assertTrue("authenticated email matches requested account",
                user != null && expectedEmail.equalsIgnoreCase(user.getEmail()));
        JSONObject profile = ProfileClient.self();
        assertTrue("Cloud profile belongs to active Auth identity",
                SessionManager.getInstance().uid().equals(profile.optString("profile_id")));
    }

    private void verifyMainNavigationAndTheme() throws Exception {
        ui.launch(MainActivity.class);
        waitForMain(FeedFragment.class, R.id.nav_feed);
        assertTrue("Feed navigation retains identity", !SessionManager.getInstance().uid().isEmpty());

        onView(withId(R.id.nav_profile)).perform(click());
        waitForMain(ProfileFragment.class, R.id.nav_profile);
        assertTrue("Profile navigation retains identity", !SessionManager.getInstance().uid().isEmpty());

        onView(withId(R.id.nav_search)).perform(click());
        waitForMain(SearchFragment.class, R.id.nav_search);
        assertTrue("Search navigation retains identity", !SessionManager.getInstance().uid().isEmpty());

        onView(withId(R.id.btnMessenger)).perform(click());
        waitForMain(InboxFragment.class, R.id.nav_messages);
        assertTrue("Messenger navigation retains identity", !SessionManager.getInstance().uid().isEmpty());

        onView(withId(R.id.nav_profile)).perform(click());
        waitForMain(ProfileFragment.class, R.id.nav_profile);
        int originalNight = ui.activity().getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        onView(withId(R.id.switchDarkMode)).perform(click());
        ui.waitFor("dark mode recreation keeps MainActivity", () -> ui.activity() instanceof MainActivity
                && ((ui.activity().getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) != originalNight));
        assertTrue("dark mode recreation retains identity", !SessionManager.getInstance().uid().isEmpty());
        onView(withId(R.id.switchDarkMode)).perform(click());
        ui.waitFor("theme restored and MainActivity remains", () -> ui.activity() instanceof MainActivity
                && ((ui.activity().getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == originalNight));
        assertTrue("restoring theme retains identity", !SessionManager.getInstance().uid().isEmpty());
    }

    private void waitForMain(Class<?> fragmentType, int selectedItem) throws Exception {
        ui.waitFor("expected top-level screen is visible", () -> {
            Activity current = ui.activity();
            if (!(current instanceof MainActivity)) return false;
            final boolean[] correct = {false};
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                MainActivity main = (MainActivity) current;
                com.google.android.material.bottomnavigation.BottomNavigationView nav =
                        main.findViewById(R.id.bottom_navigation);
                correct[0] = fragmentType.isInstance(main.getSupportFragmentManager()
                        .findFragmentById(R.id.fragment_container))
                        && nav.getSelectedItemId() == selectedItem;
            });
            return correct[0];
        });
    }

    private static org.hamcrest.Matcher<android.view.View> withTextSafe(String text) {
        return org.hamcrest.Matchers.allOf(androidx.test.espresso.matcher.ViewMatchers.withText(text),
                androidx.test.espresso.matcher.ViewMatchers.isDisplayed());
    }
}
