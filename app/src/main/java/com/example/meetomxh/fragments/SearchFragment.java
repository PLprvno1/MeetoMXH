package com.example.meetomxh.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.*;
import com.example.meetomxh.ProfileActivity;
import com.example.meetomxh.R;
import com.example.meetomxh.databinding.FragmentSearchBinding;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.adapters.SearchPostAdapter;
import com.example.meetomxh.repositories.AdvancedSearchRepository;
import com.example.meetomxh.supabase.*;
import com.example.meetomxh.ui.AvatarViews;
import com.google.android.material.imageview.ShapeableImageView;
import org.json.*;
import java.util.*;

/** Shared by the global navigation tab and SearchActivity. Server-side cursor paging. */
public class SearchFragment extends Fragment {
    private FragmentSearchBinding binding;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final List<JSONObject> searchUsers=new ArrayList<>();
    private final List<Post> searchPosts=new ArrayList<>();
    private SearchResultAdapter people;private SearchPostAdapter posts;
    private Subscription request;private Runnable pending;
    private String query="",afterName,afterId,afterTime;private float afterRank=-1;
    private boolean users,busy,more,lastAppend;private int generation;
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,@Nullable ViewGroup container,@Nullable Bundle state){binding=FragmentSearchBinding.inflate(inflater,container,false);return binding.getRoot();}
    @Override public void onViewCreated(@NonNull View view,@Nullable Bundle state){
        super.onViewCreated(view,state);
        if(state!=null){query=state.getString("query","");users=state.getBoolean("users");}
        people=new SearchResultAdapter(searchUsers);posts=new SearchPostAdapter(post->PostDetails.show(requireActivity(),post.getPostId()),false);
        binding.searchList.setLayoutManager(new LinearLayoutManager(requireContext()));binding.searchList.setAdapter(users?people:posts);
        binding.searchTabs.addTab(binding.searchTabs.newTab().setText("Bài viết"));binding.searchTabs.addTab(binding.searchTabs.newTab().setText("Người dùng"));
        binding.searchTabs.getTabAt(users?1:0).select();binding.etSearchExplore.setText(query);
        binding.etSearchExplore.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
        binding.searchTabs.addOnTabSelectedListener(new com.google.android.material.tabs.TabLayout.OnTabSelectedListener(){
            public void onTabSelected(com.google.android.material.tabs.TabLayout.Tab tab){users=tab.getPosition()==1;binding.searchList.setAdapter(users?people:posts);restart(false);}
            public void onTabUnselected(com.google.android.material.tabs.TabLayout.Tab tab){}public void onTabReselected(com.google.android.material.tabs.TabLayout.Tab tab){}
        });
        binding.etSearchExplore.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int start,int before,int count){query=s.toString().trim();restart(true);}});
        binding.btnClearSearch.setOnClickListener(v->binding.etSearchExplore.setText(""));
        binding.etSearchExplore.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){restart(false);return true;}return false;});
        binding.btnLoadMore.setOnClickListener(v->load(true));binding.btnRetry.setOnClickListener(v->load(lastAppend));
        binding.swipeSearch.setOnRefreshListener(()->restart(false));restart(false);
    }
    private void restart(boolean debounce){
        binding.etSearchExplore.setHint(users?"Tìm tên hoặc biệt danh…":"Tìm bài viết…");
        generation++;if(request!=null)request.remove();if(pending!=null)handler.removeCallbacks(pending);busy=false;more=false;lastAppend=false;
        afterName=null;afterId=null;afterTime=null;afterRank=-1;searchUsers.clear();searchPosts.clear();people.notifyDataSetChanged();posts.setRows(searchPosts);
        binding.btnClearSearch.setVisibility(query.isEmpty()?View.GONE:View.VISIBLE);binding.btnLoadMore.setVisibility(View.GONE);binding.btnRetry.setVisibility(View.GONE);
        binding.swipeSearch.setRefreshing(false);binding.progressSearch.setVisibility(View.GONE);
        if(query.length()<2 && (!query.isEmpty()||users)){status("Nhập ít nhất 2 ký tự để tìm "+(users?"tài khoản":"bài viết")+".");return;}
        status(query.isEmpty()?"Đang tải nội dung khám phá…":"Đang tìm kiếm…");
        int token=generation;pending=()->{if(binding!=null&&token==generation)load(false);};handler.postDelayed(pending,debounce?350:0);
    }
    private void load(boolean append){
        if(binding==null||busy||append&&!more||query.length()<2&&(!query.isEmpty()||users))return;
        busy=true;lastAppend=append;int token=++generation;binding.progressSearch.setVisibility(View.VISIBLE);binding.btnRetry.setVisibility(View.GONE);binding.btnLoadMore.setEnabled(false);
        if(!append)status(query.isEmpty()?"Đang tải nội dung khám phá…":"Đang tìm kiếm…");
        if(users)request=AdvancedSearchRepository.people(query,append?afterName:null,append?afterId:null,20,new AdvancedSearchRepository.Callback<JSONArray>(){
            public void success(JSONArray page){if(!active(token))return;if(!append)searchUsers.clear();Set<String> ids=new HashSet<>();for(JSONObject p:searchUsers)ids.add(p.optString("profile_id"));for(int n=0;n<page.length();n++){JSONObject p=page.optJSONObject(n);if(p!=null&&ids.add(p.optString("profile_id")))searchUsers.add(p);}if(page.length()>0){JSONObject last=page.optJSONObject(page.length()-1);afterName=last.optString("cursor_name");afterId=last.optString("profile_id");}more=page.length()==20;people.notifyDataSetChanged();done(searchUsers.isEmpty());}
            public void error(String message){failed(token,message);}
        });
        else request=AdvancedSearchRepository.posts(query,false,null,query.isEmpty()?"newest":"relevance",append&&!query.isEmpty()?afterRank:-1,append?afterTime:null,append?afterId:null,20,new AdvancedSearchRepository.Callback<AdvancedSearchRepository.PostPage>(){
            public void success(AdvancedSearchRepository.PostPage page){if(!active(token))return;if(!append)searchPosts.clear();Set<String> ids=new HashSet<>();for(Post p:searchPosts)ids.add(p.getPostId());for(Post p:page.posts)if(ids.add(p.getPostId()))searchPosts.add(p);afterId=page.afterId;afterTime=page.afterTime;afterRank=page.afterRank;more=page.posts.size()==20;posts.setRows(searchPosts);done(searchPosts.isEmpty());}
            public void error(String message){failed(token,message);}
        });
    }
    private boolean active(int token){return binding!=null&&isAdded()&&token==generation;}
    private void done(boolean empty){busy=false;binding.progressSearch.setVisibility(View.GONE);binding.swipeSearch.setRefreshing(false);binding.btnLoadMore.setEnabled(true);binding.btnLoadMore.setVisibility(more?View.VISIBLE:View.GONE);status(empty?"Không tìm thấy kết quả.":"");}
    private void failed(int token,String message){if(!active(token))return;busy=false;binding.progressSearch.setVisibility(View.GONE);binding.swipeSearch.setRefreshing(false);binding.btnLoadMore.setVisibility(View.GONE);status(message);binding.btnRetry.setVisibility(View.VISIBLE);}
    private void status(String text){binding.searchStatus.setText(text);binding.searchStatus.setVisibility(text.isEmpty()?View.GONE:View.VISIBLE);}
    @Override public void onResume(){super.onResume();PostViewerUpdates.returning(requireActivity());}
    @Override public void onSaveInstanceState(@NonNull Bundle out){super.onSaveInstanceState(out);out.putString("query",query);out.putBoolean("users",users);}
    @Override public void onDestroyView(){generation++;if(request!=null)request.remove();handler.removeCallbacksAndMessages(null);binding.searchList.setAdapter(null);binding=null;super.onDestroyView();}
    // Search Result Adapter (People & Accounts)
    private class SearchResultAdapter extends RecyclerView.Adapter<SearchResultAdapter.Holder> {
        private final List<JSONObject> items;
        SearchResultAdapter(List<JSONObject> items) { this.items = items; }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_inbox, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            JSONObject user = items.get(position);
            String name = user.optString("display_name", user.optString("full_name", "Người dùng"));
            // Advanced Search returns profile_id; retain legacy aliases for older payloads.
            String uid = user.optString("profile_id", user.optString("id", user.optString("uid", "")));
            String avatarAsset = user.optString("avatar_asset_id", null);
            String preset = user.optString("avatar_preset", "neutral");

            holder.name.setText(SearchPostAdapter.clean(name,"Người dùng"));
            String username=SearchPostAdapter.clean(Dto.nullable(user,"username"),"");
            holder.detail.setText((username.isEmpty()?"Thành viên Meeto":"@"+username)+(user.optBoolean("following")?" · Đang theo dõi":""));
            holder.time.setVisibility(View.GONE);
            holder.unread.setVisibility(View.GONE);

            AvatarViews.load(holder.avatar, Dto.asset(avatarAsset), preset);

            holder.itemView.setOnClickListener(v -> {
                if (!uid.isEmpty()) {
                    startActivity(new Intent(requireContext(), ProfileActivity.class).putExtra("target_uid", uid));
                }
            });
        }

        @Override
        public int getItemCount() { return items.size(); }

        class Holder extends RecyclerView.ViewHolder {
            final ShapeableImageView avatar;
            final TextView name, detail, time;
            final View unread;
            Holder(View v) {
                super(v);
                avatar = v.findViewById(R.id.ivAvatar);
                name = v.findViewById(R.id.tvChatName);
                detail = v.findViewById(R.id.tvLastMessage);
                time = v.findViewById(R.id.tvTimestamp);
                unread = v.findViewById(R.id.viewUnread);
            }
        }
    }
}
