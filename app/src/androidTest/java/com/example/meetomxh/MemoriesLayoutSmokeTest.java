package com.example.meetomxh;

import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertNotNull;

@RunWith(AndroidJUnit4.class)
public final class MemoriesLayoutSmokeTest {
    @Test public void memoriesHomeInflatesItsMainSurfaces() {
        ContextThemeWrapper themed=new ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),R.style.Theme_MeetoMXH);
        View root=LayoutInflater.from(themed).inflate(R.layout.activity_memories,null,false);
        assertNotNull(root.findViewById(R.id.btnToday));
        assertNotNull(root.findViewById(R.id.btnYears));
        assertNotNull(root.findViewById(R.id.btnHidden));
        assertNotNull(root.findViewById(R.id.layoutSettings));
        assertNotNull(root.findViewById(R.id.rvMemories));
        assertNotNull(root.findViewById(R.id.tvEmpty));
        assertNotNull(root.findViewById(R.id.btnLoadMore));
    }
}
