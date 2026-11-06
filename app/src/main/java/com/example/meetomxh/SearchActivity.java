package com.example.meetomxh;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.meetomxh.adapters.FeedAdapter;
import com.example.meetomxh.databinding.ActivitySearchBinding;
import com.example.meetomxh.fragments.CommentsBottomSheetFragment;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.repositories.AdvancedSearchRepository;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Dto;
import com.example.meetomxh.supabase.PostDetails;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.ui.AvatarViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class SearchActivity extends AppCompatActivity implements FeedAdapter.OnPostClickListener {
    private static final int ALL=0, PEOPLE=1, POSTS=2, HISTORY=3;
    private static final int ALL_LIMIT=5, PAGE_LIMIT=20;
    private ActivitySearchBinding binding;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;
    private int generation=0,tab=ALL,pendingInitial=0;
    private String query="",sort="relevance",afterName,peopleAfterTime,peopleAfterId,postAfterTime,postAfterId;
    private float postAfterRank=-1f;
    private boolean mediaOnly,hasMorePeople,hasMorePosts,loadingMore,spinnersReady,searchError;
    private boolean resumedOnce;
    private String since;
    private JSONArray people=new JSONArray(),history=new JSONArray();
    private List<Post> posts=new ArrayList<>();
    private PeopleAdapter peopleAdapter;
    private FeedAdapter postAdapter;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding=ActivitySearchBinding.inflate(getLayoutInflater());setContentView(binding.getRoot());
        if(savedInstanceState!=null){query=savedInstanceState.getString("query","");tab=savedInstanceState.getInt("tab",ALL);}
        setup();
        if(tab!=ALL)binding.searchTabs.check(tab==PEOPLE?R.id.tabPeople:R.id.tabPosts);
        if(!query.isEmpty())binding.etQuery.setText(query);
        loadHistory();
        if(query.length()>=2) { if(pendingSearch!=null)handler.removeCallbacks(pendingSearch);binding.etQuery.post(()->runSearch(false)); }
        else showCurrent();
    }

    private void setup(){
        binding.btnBack.setOnClickListener(v->finish());
        binding.btnClear.setOnClickListener(v->{binding.etQuery.setText("");binding.etQuery.requestFocus();});
        binding.searchTabs.check(R.id.tabAll);
        binding.searchTabs.addOnButtonCheckedListener((group,checkedId,isChecked)->{
            if(!isChecked)return;
            tab=checkedId==R.id.tabPeople?PEOPLE:checkedId==R.id.tabPosts?POSTS:ALL;
            showCurrent();
            if(query.length()>=2 && (tab==PEOPLE&&people.length()==0 || tab==POSTS&&posts.isEmpty())) runSearch(false);
        });
        binding.etQuery.setOnEditorActionListener((v,action,event)->{
            if(action==EditorInfo.IME_ACTION_SEARCH){submitQuery(binding.etQuery.getText().toString());return true;}return false;
        });
        binding.etQuery.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){
                generation++;if(pendingSearch!=null)handler.removeCallbacks(pendingSearch);
                query=s.toString().trim();searchError=false;pendingInitial=query.length()>=2?2:0;binding.btnClear.setVisibility(query.isEmpty()?View.GONE:View.VISIBLE);
                resetPages();
                if(query.length()<2){showCurrent();return;}
                setStatus("Searching…",false);showCurrent();
                final int token=generation;
                pendingSearch=()->{if(token==generation)runSearch(false);};handler.postDelayed(pendingSearch,400);
            }
            @Override public void afterTextChanged(Editable s){}
        });
        binding.btnLoadMore.setOnClickListener(v->loadMore());
        binding.tvStatus.setOnClickListener(v->{if(query.length()>=2)runSearch(false);else loadHistory();});
        binding.swipeSearch.setOnRefreshListener(()->{if(query.length()>=2)runSearch(false);else loadHistory();});
        setupFilters();

        peopleAdapter=new PeopleAdapter(this::openProfile);
        postAdapter=new FeedAdapter(this,false);
        binding.rvPeople.setLayoutManager(new LinearLayoutManager(this));binding.rvPeople.setAdapter(peopleAdapter);
        binding.rvPosts.setLayoutManager(new LinearLayoutManager(this));binding.rvPosts.setAdapter(postAdapter);
    }

    private void setupFilters(){
        String[] sorts={"Most relevant","Newest"};
        android.widget.ArrayAdapter<String> sortAdapter=new android.widget.ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,sorts);
        binding.spinnerSort.setAdapter(sortAdapter);
        String[] dates={"Any time","24 hours","7 days","30 days"};
        android.widget.ArrayAdapter<String> dateAdapter=new android.widget.ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,dates);
        binding.spinnerDate.setAdapter(dateAdapter);
        binding.spinnerSort.setSelection(0);binding.spinnerDate.setSelection(0);spinnersReady=true;
        android.widget.AdapterView.OnItemSelectedListener change=new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){if(!spinnersReady||tab!=POSTS)return;applyFiltersAndSearch();}
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent){}
        };
        binding.spinnerSort.setOnItemSelectedListener(change);binding.spinnerDate.setOnItemSelectedListener(change);
        binding.checkMediaOnly.setOnCheckedChangeListener((button,checked)->{if(spinnersReady&&tab==POSTS)applyFiltersAndSearch();});
    }

    private void applyFiltersAndSearch(){
        sort=binding.spinnerSort.getSelectedItemPosition()==1?"newest":"relevance";
        mediaOnly=binding.checkMediaOnly.isChecked();
        int days=binding.spinnerDate.getSelectedItemPosition();
        if(days==0)since=null;else{Calendar c=Calendar.getInstance(TimeZone.getTimeZone("UTC"));c.add(Calendar.DAY_OF_YEAR,-(days==1?1:days==2?7:30));java.text.SimpleDateFormat fmt=new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);fmt.setTimeZone(TimeZone.getTimeZone("UTC"));since=fmt.format(c.getTime());}
        generation++;if(pendingSearch!=null)handler.removeCallbacks(pendingSearch);resetPostPage();runSearch(false);
    }

    private void submitQuery(String text){
        String value=text==null?"":text.trim();if(value.length()<2){setStatus("Enter at least 2 characters to search.",false);return;}
        if(!value.equals(query)){binding.etQuery.setText(value);}
        query=value;generation++;if(pendingSearch!=null)handler.removeCallbacks(pendingSearch);
        AdvancedSearchRepository.save(query,new AdvancedSearchRepository.Callback<JSONObject>(){
            @Override public void success(JSONObject saved){loadHistory();}
            @Override public void error(String message){Toast.makeText(SearchActivity.this,"Search ran, but history could not be saved.",Toast.LENGTH_SHORT).show();}
        });
        runSearch(false);
        ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(binding.etQuery.getWindowToken(),0);
    }

    private void resetPages(){people=new JSONArray();posts=new ArrayList<>();resetPeoplePage();resetPostPage();}
    private void resetPeoplePage(){afterName=null;peopleAfterTime=null;peopleAfterId=null;hasMorePeople=false;}

    private void resetPostPage(){postAfterTime=null;postAfterId=null;postAfterRank=-1f;hasMorePosts=false;}

    private void runSearch(boolean append){
        if(query.trim().length()<2){showCurrent();return;}
        final int token=generation;
        if(!append){resetPages();searchError=false;}
        loadingMore=append;pendingInitial=append?1:2;binding.swipeSearch.setRefreshing(append);
        if(!append)setStatus("Searching…",false);showCurrent();
        if(!append){
            loadPeople(token,false,ALL_LIMIT);loadPosts(token,false,ALL_LIMIT);
        }else if(tab==PEOPLE)loadPeople(token,true,PAGE_LIMIT);
        else if(tab==POSTS)loadPosts(token,true,PAGE_LIMIT);
    }

    private void loadPeople(int token,boolean append,int limit){
        AdvancedSearchRepository.people(query,append?afterName:null,append?peopleAfterId:null,limit,new AdvancedSearchRepository.Callback<JSONArray>(){
            @Override public void success(JSONArray page){if(token!=generation)return;binding.swipeSearch.setRefreshing(false);if(!append)people=new JSONArray();merge(people,page);hasMorePeople=page.length()==limit;updatePeopleCursor(page);if(!append)finishInitial();else{loadingMore=false;showCurrent();}}
            @Override public void error(String message){if(token!=generation)return;binding.swipeSearch.setRefreshing(false);markError("People search failed. Tap to retry.",append);}
        });
    }
    private void loadPosts(int token,boolean append,int limit){
        AdvancedSearchRepository.posts(query,mediaOnly,since,sort,append&&"relevance".equals(sort)?postAfterRank:-1f,
                append?postAfterTime:null,append?postAfterId:null,limit,new AdvancedSearchRepository.Callback<AdvancedSearchRepository.PostPage>(){
            @Override public void success(AdvancedSearchRepository.PostPage page){if(token!=generation)return;binding.swipeSearch.setRefreshing(false);if(!append)posts=new ArrayList<>();posts.addAll(page.posts);hasMorePosts=page.posts.size()==limit;if(!page.posts.isEmpty()){postAfterRank=page.afterRank;postAfterTime=page.afterTime;postAfterId=page.afterId;}if(!append)finishInitial();else{loadingMore=false;showCurrent();}}
            @Override public void error(String message){if(token!=generation)return;binding.swipeSearch.setRefreshing(false);markError("Post search failed. Tap to retry.",append);}
        });
    }


    private void finishInitial(){pendingInitial=Math.max(0,pendingInitial-1);if(pendingInitial==0){if(!searchError)setStatus("",false);showCurrent();}}
    private void markError(String message,boolean append){loadingMore=false;searchError=true;setStatus(message,true);if(!append)finishInitial();showCurrent();}
    private void updatePeopleCursor(JSONArray page){if(page.length()>0){JSONObject last=page.optJSONObject(page.length()-1);afterName=last.optString("cursor_name");peopleAfterId=last.optString("profile_id");}}

    private static void merge(JSONArray into,JSONArray page){for(int i=0;i<page.length();i++)into.put(page.opt(i));}
    private void loadMore(){if(loadingMore)return;int token=generation;if(tab==PEOPLE&&hasMorePeople)loadPeople(token,true,PAGE_LIMIT);else if(tab==POSTS&&hasMorePosts)loadPosts(token,true,PAGE_LIMIT);}

    private void loadHistory(){
        AdvancedSearchRepository.history(new AdvancedSearchRepository.Callback<JSONArray>(){
            @Override public void success(JSONArray rows){history=rows;showCurrent();}
            @Override public void error(String message){history=new JSONArray();if(query.isEmpty())setStatus("Search history is unavailable. Tap to retry.",true);showCurrent();}
        });
    }

    private void showCurrent(){
        if(binding==null)return;
        binding.allScroll.setVisibility(tab==ALL?View.VISIBLE:View.GONE);
        binding.rvPeople.setVisibility(tab==PEOPLE?View.VISIBLE:View.GONE);
        binding.rvPosts.setVisibility(tab==POSTS?View.VISIBLE:View.GONE);
        binding.historyScroll.setVisibility(tab==HISTORY?View.VISIBLE:View.GONE);
        binding.postFilters.setVisibility(tab==POSTS?View.VISIBLE:View.GONE);
        binding.btnLoadMore.setVisibility(tab==PEOPLE&&hasMorePeople||tab==POSTS&&hasMorePosts?View.VISIBLE:View.GONE);
        peopleAdapter.setItems(people);postAdapter.setPosts(posts);
        if(tab==ALL)renderAll();if(tab==HISTORY)renderHistory();
        if(query.length()<2&&tab!=HISTORY){binding.tvStatus.setVisibility(View.VISIBLE);binding.tvStatus.setText("Tìm người, bài viết và nhóm. Nhập ít nhất 2 ký tự.");}
        else if(query.length()>=2&&pendingInitial==0&&!searchError){boolean empty=tab==PEOPLE?people.length()==0:tab==POSTS?posts.isEmpty():false;if(empty)setStatus("Không có kết quả trong mục này.",false);}
    }

    private void renderAll(){
        binding.historySection.removeAllViews();binding.peopleSection.removeAllViews();binding.postsSection.removeAllViews();
        if(query.length()<2){renderHistoryRows(binding.historySection,true);return;}
        addSectionTitle(binding.peopleSection,"Mọi người",()->selectTab(PEOPLE));
        for(int i=0;i<Math.min(people.length(),ALL_LIMIT);i++)addPersonSummary(binding.peopleSection,people.optJSONObject(i));
        if(people.length()==0)addEmpty(binding.peopleSection,"Không tìm thấy người phù hợp");
        addSectionTitle(binding.postsSection,"Bài viết",()->selectTab(POSTS));
        for(int i=0;i<Math.min(posts.size(),ALL_LIMIT);i++)addPostSummary(binding.postsSection,posts.get(i));
        if(posts.isEmpty())addEmpty(binding.postsSection,"Không tìm thấy bài viết phù hợp");
        if(searchError)setStatus("Một số kết quả chưa tải được. Chạm để thử lại.",true);
        else if(pendingInitial>0)setStatus("Đang tìm kiếm…",false);
        else setStatus("",false);
    }

    private void renderHistory(){binding.historyRows.removeAllViews();renderHistoryRows(binding.historyRows,false);}
    private void renderHistoryRows(LinearLayout target,boolean compact){
        if(history.length()==0){addEmpty(target,"Từ khóa đã tìm sẽ hiện ở đây.");return;}
        addSectionTitle(target,"Tìm kiếm gần đây","Xóa tất cả",()->new androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage("Xóa toàn bộ lịch sử tìm kiếm?").setNegativeButton("Hủy",null).setPositiveButton("Xóa",(d,w)->clearHistory()).show());
        for(int i=0;i<history.length();i++){
            JSONObject row=history.optJSONObject(i);if(row==null)continue;
            LinearLayout item=new LinearLayout(this);item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(12,8,8,8);item.setBackgroundResource(R.drawable.bg_card_rounded);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=8;target.addView(item,lp);
            TextView text=new TextView(this);text.setText("◷  "+row.optString("query"));text.setTextSize(16);text.setTextColor(getColor(R.color.text_primary));text.setPadding(8,12,8,12);item.addView(text,new LinearLayout.LayoutParams(0,-2,1));
            TextView remove=new TextView(this);remove.setText("×");remove.setTextSize(22);remove.setPadding(12,8,12,8);remove.setContentDescription("Xóa mục lịch sử tìm kiếm");item.addView(remove);
            View.OnClickListener reuse=v->submitQuery(row.optString("query"));text.setOnClickListener(reuse);item.setOnClickListener(reuse);
            remove.setOnClickListener(v->AdvancedSearchRepository.deleteHistory(row.optString("id"),new AdvancedSearchRepository.Callback<JSONObject>(){
                @Override public void success(JSONObject x){loadHistory();}
                @Override public void error(String e){Toast.makeText(SearchActivity.this,"Không xóa được mục này.",Toast.LENGTH_SHORT).show();}
            }));
        }
    }
    private void clearHistory(){AdvancedSearchRepository.clearHistory(new AdvancedSearchRepository.Callback<JSONObject>(){
        @Override public void success(JSONObject v){loadHistory();}
        @Override public void error(String e){Toast.makeText(SearchActivity.this,"Không xóa được lịch sử.",Toast.LENGTH_SHORT).show();}
    });}

    private void addSectionTitle(LinearLayout parent,String title,Runnable more){
        addSectionTitle(parent,title,"Xem tất cả",more);
    }
    private void addSectionTitle(LinearLayout parent,String title,String actionLabel,Runnable more){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(4,12,4,4);parent.addView(row,new LinearLayout.LayoutParams(-1,-2));
        TextView label=new TextView(this);label.setText(title);label.setTextSize(18);label.setTypeface(null,Typeface.BOLD);label.setTextColor(getColor(R.color.text_primary));row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        TextView see=new TextView(this);see.setText(actionLabel);see.setTextColor(getColor(R.color.brand_blue));see.setPadding(10,8,6,8);row.addView(see);see.setOnClickListener(v->more.run());
    }
    private void addEmpty(LinearLayout parent,String value){TextView label=new TextView(this);label.setText(value);label.setTextColor(getColor(R.color.text_secondary));label.setPadding(12,8,12,12);parent.addView(label);}
    private void addPersonSummary(LinearLayout parent,JSONObject row){
        if(row==null)return;LinearLayout card=summaryCard(parent);ImageView avatar=new ImageView(this);avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);LinearLayout.LayoutParams al=new LinearLayout.LayoutParams(48,48);card.addView(avatar,al);AvatarViews.load(avatar,Dto.asset(Dto.nullable(row,"avatar_asset_id")),row.optString("avatar_preset","neutral"));
        LinearLayout text=new LinearLayout(this);text.setOrientation(LinearLayout.VERTICAL);text.setPadding(12,0,0,0);card.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        addText(text,row.optString("display_name"),16,true);String state=relationLabel("")+(row.optBoolean("following")?" · Đang theo dõi":"");addText(text,state,13,false);
        card.setOnClickListener(v->openProfile(row.optString("profile_id")));
    }
    private String relationLabel(String s){return "Thành viên Meeto";}
    private void addPostSummary(LinearLayout parent,Post post){
        LinearLayout card=summaryCard(parent);addText(card,post.getUserName(),14,true);String body=post.getContent().trim();if(body.isEmpty())body="Bài viết có ảnh hoặc video";if(body.length()>160)body=body.substring(0,157)+"…";addText(card,body,15,false);
        addText(card,post.getLikesCount()+" lượt cảm xúc · "+post.getCommentsCount()+" bình luận",13,false);card.setOnClickListener(v->PostDetails.show(this,post.getPostId()));
    }

    private LinearLayout summaryCard(LinearLayout parent){LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(14,12,14,12);card.setBackgroundResource(R.drawable.bg_card_rounded);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=8;parent.addView(card,lp);return card;}
    private void addText(LinearLayout parent,String value,int size,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(getColor(R.color.text_primary));if(bold)t.setTypeface(null,Typeface.BOLD);t.setPadding(2,3,2,3);parent.addView(t);}

    private void selectTab(int value){tab=value;binding.searchTabs.check(value==PEOPLE?R.id.tabPeople:value==POSTS?R.id.tabPosts:R.id.tabAll);showCurrent();}
    private void showStatus(String text,boolean retry){binding.tvStatus.setText(text);binding.tvStatus.setVisibility(text==null||text.isEmpty()?View.GONE:View.VISIBLE);binding.tvStatus.setClickable(retry);}
    private void setStatus(String text,boolean retry){showStatus(text,retry);binding.progressSearch.setVisibility("Searching…".equals(text)?View.VISIBLE:View.GONE);}
    private void openProfile(String id){if(id!=null&&!id.isEmpty())startActivity(new Intent(this,ProfileActivity.class).putExtra("target_uid",id));}

    @Override protected void onSaveInstanceState(@NonNull Bundle out){out.putString("query",query);out.putInt("tab",tab);super.onSaveInstanceState(out);}
    @Override protected void onResume(){super.onResume();if(resumedOnce&&query.length()>=2)runSearch(false);resumedOnce=true;}
    @Override protected void onDestroy(){if(pendingSearch!=null)handler.removeCallbacks(pendingSearch);binding=null;super.onDestroy();}

    @Override public void onLikeClick(Post post,Runnable done){Api.run(()->{RpcClient.call("set_post_reaction",Api.obj("p_post_id",post.getPostId(),"p_reaction",post.isLiked()?null:"like"));return true;},new Api.Callback<Boolean>(){
        @Override public void success(Boolean v){done.run();runSearch(false);}
        @Override public void error(String e){done.run();Toast.makeText(SearchActivity.this,e,Toast.LENGTH_LONG).show();}
    });}
    @Override public void onCommentClick(Post post){CommentsBottomSheetFragment.newInstance(post.getPostId()).show(getSupportFragmentManager(),"SearchComments");}
    @Override public void onShareClick(Post post){com.example.meetomxh.supabase.ShareActions.show(this,post,false);}
    @Override public void onCreatePostClick(){Toast.makeText(this,"Create posts from the feed.",Toast.LENGTH_SHORT).show();}
    @Override public void onAuthorClick(Post post){openProfile(post.getAuthorUid());}
    @Override public void onDeletePostClick(Post post){}
    @Override public void onSavePostClick(Post post,boolean save,Runnable done){Api.run(()->{RpcClient.call("set_saved_post",Api.obj("p_post_id",post.getPostId(),"p_saved",save));return true;},new Api.Callback<Boolean>(){
        @Override public void success(Boolean v){done.run();}
        @Override public void error(String e){done.run();Toast.makeText(SearchActivity.this,e,Toast.LENGTH_LONG).show();}
    });}

    private static final class PeopleAdapter extends RecyclerView.Adapter<PeopleHolder>{
        interface Click{void open(String id);}private final Click click;private final List<JSONObject> rows=new ArrayList<>();
        PeopleAdapter(Click c){click=c;}void setItems(JSONArray data){rows.clear();for(int i=0;i<data.length();i++)if(data.optJSONObject(i)!=null)rows.add(data.optJSONObject(i));notifyDataSetChanged();}
        @NonNull @Override public PeopleHolder onCreateViewHolder(@NonNull ViewGroup parent,int type){LinearLayout row=new LinearLayout(parent.getContext());row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(12,10,12,10);row.setBackgroundResource(R.drawable.bg_card_rounded);ImageView avatar=new ImageView(parent.getContext());avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);row.addView(avatar,new LinearLayout.LayoutParams(52,52));LinearLayout txt=new LinearLayout(parent.getContext());txt.setOrientation(LinearLayout.VERTICAL);txt.setPadding(12,0,0,0);row.addView(txt,new LinearLayout.LayoutParams(0,-2,1));TextView name=new TextView(parent.getContext());name.setTextSize(16);name.setTypeface(null,Typeface.BOLD);name.setTextColor(parent.getContext().getColor(R.color.text_primary));txt.addView(name);TextView meta=new TextView(parent.getContext());meta.setTextSize(13);meta.setTextColor(parent.getContext().getColor(R.color.text_secondary));txt.addView(meta);return new PeopleHolder(row,avatar,name,meta);}
        @Override public void onBindViewHolder(@NonNull PeopleHolder h,int p){JSONObject row=rows.get(p);h.name.setText(row.optString("display_name"));h.meta.setText(relationLabelStatic("")+(row.optBoolean("following")?" · Đang theo dõi":""));AvatarViews.load(h.avatar,Dto.asset(Dto.nullable(row,"avatar_asset_id")),row.optString("avatar_preset","neutral"));h.itemView.setOnClickListener(v->click.open(row.optString("profile_id")));}
        @Override public int getItemCount(){return rows.size();}
    }
    private static String relationLabelStatic(String s){return "Thành viên Meeto";}
    private static final class PeopleHolder extends RecyclerView.ViewHolder{ImageView avatar;TextView name,meta;PeopleHolder(View v,ImageView a,TextView n,TextView m){super(v);avatar=a;name=n;meta=m;}}

}
