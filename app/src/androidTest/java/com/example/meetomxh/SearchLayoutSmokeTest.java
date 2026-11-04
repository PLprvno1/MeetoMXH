package com.example.meetomxh;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ContextThemeWrapper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertNotNull;

@RunWith(AndroidJUnit4.class)
public final class SearchLayoutSmokeTest {
    @Test public void searchHomeLayoutInflatesWithAllResultSurfaces() {
        ContextThemeWrapper themed=new ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext(),R.style.Theme_MeetoMXH);
        View root=LayoutInflater.from(themed)
                .inflate(R.layout.activity_search,null,false);
        assertNotNull(root.findViewById(R.id.etQuery));
        assertNotNull(root.findViewById(R.id.searchTabs));
        assertNotNull(root.findViewById(R.id.progressSearch));
        assertNotNull(root.findViewById(R.id.allScroll));
        assertNotNull(root.findViewById(R.id.rvPeople));
        assertNotNull(root.findViewById(R.id.rvPosts));
        assertNotNull(root.findViewById(R.id.rvGroups));
        assertNotNull(root.findViewById(R.id.historyRows));
    }
}
