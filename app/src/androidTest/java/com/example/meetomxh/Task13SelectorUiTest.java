package com.example.meetomxh;

import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

/** UI-only selector checks; deliberately does not submit signup or onboarding. */
@RunWith(AndroidJUnit4.class)
public class Task13SelectorUiTest {
    @Test public void dateGenderAndLocationSelectorsOpenAndRemainOptional() {
        Intent intent=new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),RegisterActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        onView(withHint("Ng\u00e0y sinh (b\u1eaft bu\u1ed9c)")).perform(click());
        onView(androidx.test.espresso.matcher.ViewMatchers.withClassName(org.hamcrest.Matchers.is(android.widget.DatePicker.class.getName()))).check(matches(isDisplayed()));
        onView(withId(android.R.id.button2)).perform(click());
        onView(withHint("Gi\u1edbi t\u00ednh (t\u00f9y ch\u1ecdn)")).perform(click());
        onView(withText("Nam")).perform(click());
        onView(withHint("N\u01a1i \u1edf hi\u1ec7n t\u1ea1i (t\u00f9y ch\u1ecdn)")).perform(click());
        int listId=android.content.res.Resources.getSystem().getIdentifier("select_dialog_listview","id","android");
        onData(org.hamcrest.Matchers.equalTo("H\u00e0 N\u1ed9i")).inAdapterView(withId(listId)).perform(click());
        onView(withHint("N\u01a1i \u1edf hi\u1ec7n t\u1ea1i (t\u00f9y ch\u1ecdn)")).check(matches(isDisplayed()));
    }
}
