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
import com.example.meetomxh.fragments.InboxFragment;
import com.example.meetomxh.fragments.ProfileFragment;
import com.google.firebase.auth.FirebaseAuth;

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
                Toast.makeText(this, "Màn hình Video đang phát triển", Toast.LENGTH_SHORT).show();
                return true;
            } else if (itemId == R.id.nav_friends) {
                loadFragment(new InboxFragment());
                return true;
            } else if (itemId == R.id.nav_notifications) {
                Toast.makeText(this, "Màn hình Thông báo đang phát triển", Toast.LENGTH_SHORT).show();
                return true;
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
                loadFragment(new InboxFragment());
            } else if (itemId == R.id.drawer_saved) {
                Toast.makeText(this, "Mục Đã lưu", Toast.LENGTH_SHORT).show();
            } else if (itemId == R.id.drawer_memories) {
                Toast.makeText(this, "Mục Kỷ niệm", Toast.LENGTH_SHORT).show();
            } else if (itemId == R.id.drawer_marketplace) {
                Toast.makeText(this, "Mục Marketplace", Toast.LENGTH_SHORT).show();
            } else if (itemId == R.id.drawer_logout) {
                FirebaseAuth.getInstance().signOut();
                Intent intent = new Intent(this, LoginActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });

        // Messenger Click
        binding.btnMessenger.setOnClickListener(v ->
                Toast.makeText(this, "Mở cuộc trò chuyện", Toast.LENGTH_SHORT).show()
        );

        // Search Click
        binding.btnSearch.setOnClickListener(v ->
                Toast.makeText(this, "Mở Tìm kiếm", Toast.LENGTH_SHORT).show()
        );

        // Handle Back Press for Drawer
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    private void loadFragment(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }
}
