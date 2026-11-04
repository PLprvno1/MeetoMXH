package com.example.meetomxh;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.example.meetomxh.databinding.ActivityMainBinding;
import com.example.meetomxh.fragments.FeedFragment;
import com.example.meetomxh.fragments.FriendsFragment;
import com.example.meetomxh.fragments.InboxFragment;
import com.example.meetomxh.fragments.ProfileFragment;
import com.example.meetomxh.fragments.ReelsFragment;
import com.example.meetomxh.fragments.SearchFragment;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Observer;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.supabase.Subscription;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private ActionBarDrawerToggle toggle;
    private Observer<JSONObject> badgeObserver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!getIntent().getBooleanExtra(RoleResolutionActivity.EXTRA_ROLE_VERIFIED, false)) {
            startActivity(new Intent(this, RoleResolutionActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            return;
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Handle System WindowInsets for edge-to-edge:
        // - Top AppBar receives status bar inset so it flushes gracefully below the status bar
        // - Bottom Navigation receives navigation bar inset (gesture pill or 3-button bar)
        //   so icons remain centered above the system bar while the background extends flush to screen bottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.drawerLayout, (v, insets) -> {
            Insets statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            Insets navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars());

            binding.appBarLayout.setPadding(0, statusBarInsets.top, 0, 0);

            int baseNavHeight = (int) (54 * getResources().getDisplayMetrics().density + 0.5f);
            android.view.ViewGroup.LayoutParams lp = binding.bottomNavigation.getLayoutParams();
            if (lp != null) {
                lp.height = baseNavHeight + navBarInsets.bottom;
                binding.bottomNavigation.setLayoutParams(lp);
            }
            binding.bottomNavigation.setPadding(
                    navBarInsets.left,
                    0,
                    navBarInsets.right,
                    navBarInsets.bottom
            );

            return insets;
        });
        ViewCompat.requestApplyInsets(binding.drawerLayout);

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }

        // Setup Drawer Toggle
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

        // Bottom Navigation listener (5 Instagram Tabs)
        binding.bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            android.view.View itemView = binding.bottomNavigation.findViewById(itemId);
            if (itemView != null) {
                itemView.animate().scaleX(1.15f).scaleY(1.15f).setDuration(110)
                        .withEndAction(() -> itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(90).start())
                        .start();
            }
            if (itemId == R.id.nav_feed) {
                loadFragment(new FeedFragment());
                return true;
            } else if (itemId == R.id.nav_video) {
                loadFragment(new ReelsFragment());
                return true;
            } else if (itemId == R.id.nav_messages) {
                loadFragment(new InboxFragment());
                return true;
            } else if (itemId == R.id.nav_search) {
                loadFragment(new SearchFragment());
                return true;
            } else if (itemId == R.id.nav_profile) {
                loadFragment(new ProfileFragment());
                return true;
            } else if (itemId == R.id.nav_friends) {
                loadFragment(new FriendsFragment());
                return true;
            } else if (itemId == R.id.nav_notifications) {
                startActivity(new Intent(this, NotificationsActivity.class));
                return true;
            }
            return false;
        });

        binding.bottomNavigation.setOnItemReselectedListener(item -> {
            if (item.getItemId() == R.id.nav_feed) {
                androidx.fragment.app.Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
                if (current instanceof com.example.meetomxh.fragments.FeedFragment) {
                    ((com.example.meetomxh.fragments.FeedFragment) current).scrollToTop();
                }
            }
        });

        // Drawer Navigation View listener
        binding.navView.setNavigationItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.drawer_friends) {
                loadFragment(new FriendsFragment());
            } else if (itemId == R.id.drawer_groups) {
                startActivity(new Intent(this, GroupsHomeActivity.class));
            } else if (itemId == R.id.drawer_saved) {
                startActivity(new Intent(this, SavedPostsActivity.class));
            } else if (itemId == R.id.drawer_memories) {
                startActivity(new Intent(this, MemoriesActivity.class));
            } else if (itemId == R.id.drawer_marketplace) {
                startActivity(new Intent(this, MarketplaceActivity.class));
            } else if (itemId == R.id.drawer_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
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

        // Top Header Actions
        if (binding.btnCreatePost != null) {
            binding.btnCreatePost.setOnClickListener(v -> startActivity(new Intent(this, CreatePostActivity.class)));
        }
        if (binding.btnNotifications != null) {
            binding.btnNotifications.setOnClickListener(v -> startActivity(new Intent(this, NotificationsActivity.class)));
        }
        binding.btnMessenger.setOnClickListener(v -> {
            binding.bottomNavigation.setSelectedItemId(R.id.nav_messages);
        });
        if (binding.btnSearch != null) {
            binding.btnSearch.setOnClickListener(v -> {
                binding.bottomNavigation.setSelectedItemId(R.id.nav_search);
            });
        }

        observeUnreadCounts();

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
        setReelsImmersive(false);
        getSupportFragmentManager().popBackStackImmediate(null,
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }

    public void setReelsImmersive(boolean immersive) {
        if (binding == null) return;
        binding.appBarLayout.setVisibility(immersive ? android.view.View.GONE : android.view.View.VISIBLE);
        binding.bottomNavigation.setVisibility(android.view.View.VISIBLE);
    }

    private void openInbox() {
        binding.bottomNavigation.setSelectedItemId(R.id.nav_messages);
    }

    private void observeUnreadCounts() {
        if (badgeObserver != null) badgeObserver.remove();
        badgeObserver = new Observer<>(() -> Api.obj(
                "notifications", RpcClient.call("get_notification_unread_count", Api.obj()),
                "messages", RpcClient.call("get_message_unread_count", Api.obj())), new Api.Callback<JSONObject>() {
            @Override public void success(JSONObject counts) {
                int notifications = (int)Math.max(0, counts.optLong("notifications"));
                int messages = (int)Math.max(0, counts.optLong("messages"));

                // Badging for Messages tab
                try {
                    com.google.android.material.badge.BadgeDrawable msgBadge = binding.bottomNavigation.getOrCreateBadge(R.id.nav_messages);
                    msgBadge.setMaxCharacterCount(3);
                    msgBadge.setNumber(messages);
                    msgBadge.setVisible(messages > 0);
                } catch (Exception ignored) {}

                // Badging for Top Notifications button
                if (binding.tvNotificationBadge != null) {
                    if (notifications <= 0) {
                        binding.tvNotificationBadge.setVisibility(android.view.View.GONE);
                    } else {
                        binding.tvNotificationBadge.setText(notifications > 99 ? "99+" : String.valueOf(notifications));
                        binding.tvNotificationBadge.setVisibility(android.view.View.VISIBLE);
                    }
                }

                if (binding.tvMessengerUnreadBadge != null) {
                    if (messages <= 0) {
                        binding.tvMessengerUnreadBadge.setVisibility(android.view.View.GONE);
                    } else {
                        binding.tvMessengerUnreadBadge.setText(messages > 99 ? "99+" : String.valueOf(messages));
                        binding.tvMessengerUnreadBadge.setVisibility(android.view.View.VISIBLE);
                    }
                }
            }
            @Override public void error(String error) {
                try {
                    binding.bottomNavigation.removeBadge(R.id.nav_messages);
                } catch (Exception ignored) {}
                if (binding.tvNotificationBadge != null) binding.tvNotificationBadge.setVisibility(android.view.View.GONE);
                if (binding.tvMessengerUnreadBadge != null) binding.tvMessengerUnreadBadge.setVisibility(android.view.View.GONE);
            }
        });
    }

    @Override protected void onDestroy() {
        if (badgeObserver != null) badgeObserver.remove();
        super.onDestroy();
    }
}
