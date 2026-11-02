package com.example.meetomxh;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.example.meetomxh.databinding.ActivityMainBinding;
import com.example.meetomxh.fragments.FeedFragment;
import com.example.meetomxh.fragments.FriendsFragment;
import com.example.meetomxh.fragments.InboxFragment;
import com.example.meetomxh.fragments.ProfileFragment;
import com.example.meetomxh.supabase.SessionManager;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private ActionBarDrawerToggle toggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Handle Status Bar WindowInsets so Top Bar flushes down nicely below status bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout, (v, insets) -> {
            Insets statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            v.setPadding(0, statusBarInsets.top, 0, 0);
            return insets;
        });

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }

        // Setup Drawer Toggle (Hamburger icon)
        toggle = new ActionBarDrawerToggle(
                this,
                binding.drawerLayout,
                binding.toolbar,
                R.string.app_name,
                R.string.app_name
        );
        binding.drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        // Default Fragment
        if (savedInstanceState == null) {
            loadFragment(new FeedFragment());
        }

        // Bottom Navigation listener
        binding.bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_feed) {
                loadFragment(new FeedFragment());
                return true;
            } else if (itemId == R.id.nav_video) {
                Toast.makeText(this, "Video feed chưa được hỗ trợ trên Supabase", Toast.LENGTH_SHORT).show();
                return false;
            } else if (itemId == R.id.nav_friends) {
                loadFragment(new FriendsFragment());
                return true;
            } else if (itemId == R.id.nav_notifications) {
                Toast.makeText(this, "Thông báo chưa được hỗ trợ trên Supabase", Toast.LENGTH_SHORT).show();
                return false;
            } else if (itemId == R.id.nav_profile) {
                loadFragment(new ProfileFragment());
                return true;
            }
            return false;
        });

        // Drawer Navigation View listener
        binding.navView.setNavigationItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.drawer_friends) {
                binding.bottomNavigation.setSelectedItemId(R.id.nav_friends);
            } else if (itemId == R.id.drawer_saved) {
                Toast.makeText(this, "Đã lưu chưa được hỗ trợ trên Supabase", Toast.LENGTH_SHORT).show();
            } else if (itemId == R.id.drawer_memories) {
                Toast.makeText(this, "Kỷ niệm chưa được hỗ trợ trên Supabase", Toast.LENGTH_SHORT).show();
            } else if (itemId == R.id.drawer_marketplace) {
                Toast.makeText(this, "Marketplace chưa được hỗ trợ trên Supabase", Toast.LENGTH_SHORT).show();
            } else if (itemId == R.id.drawer_logout) {
                SessionManager.getInstance().signOut();
                Intent intent = new Intent(this, LoginActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });

        // Messenger Click
        binding.btnMessenger.setOnClickListener(v -> openInbox());

        binding.btnSearch.setOnClickListener(v -> {
            FriendsFragment searchFragment = new FriendsFragment();
            Bundle arguments = new Bundle();
            arguments.putBoolean(FriendsFragment.ARG_OPEN_SEARCH, true);
            searchFragment.setArguments(arguments);
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, searchFragment)
                    .addToBackStack("user_search")
                    .commit();
        });

        // Handle Back Press for Drawer
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START);
                } else {
                    setEnabled(false);
                    try {
                        getOnBackPressedDispatcher().onBackPressed();
                    } finally {
                        setEnabled(true);
                    }
                }
            }
        });
    }

    private void loadFragment(Fragment fragment) {
        getSupportFragmentManager().popBackStackImmediate(null,
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }

    private void openInbox() {
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        if (current instanceof InboxFragment) return;
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, new InboxFragment())
                .addToBackStack("inbox")
                .commit();
    }
}
