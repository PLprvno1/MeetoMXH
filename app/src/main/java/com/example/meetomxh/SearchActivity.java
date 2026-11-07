package com.example.meetomxh;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.example.meetomxh.fragments.SearchFragment;

/** Both global entry points reuse the same two-tab search controller/repository. */
public class SearchActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);setContentView(R.layout.activity_search);
        findViewById(R.id.btnBack).setOnClickListener(v->finish());
        if(state==null)getSupportFragmentManager().beginTransaction().replace(R.id.searchHost,new SearchFragment()).commit();
    }
}