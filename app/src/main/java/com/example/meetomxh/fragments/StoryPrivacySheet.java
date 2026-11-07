package com.example.meetomxh.fragments;

import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.example.meetomxh.models.Story;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.StoryClient;
import com.example.meetomxh.supabase.Subscription;
import com.example.meetomxh.ui.StoryRings;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.radiobutton.MaterialRadioButton;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** Changes only audience; options and current value are read from the owning RPC. */
public final class StoryPrivacySheet extends BottomSheetDialogFragment {
    public static final String TAG="story_privacy", RESULT="story_privacy_closed";
    private static final String[] VALUES={"public","followers","private"};
    private static final String[] LABELS={"Công khai","Người theo dõi được phê duyệt","Chỉ mình tôi"};
    private TextView currentLabel,status;
    private RadioGroup choices;
    private MaterialButton save,reload,close;
    private final Set<String> allowed=new HashSet<>();
    private String current,selected,confirmed;
    private boolean busy;
    private Subscription request;
    private int generation;

    public static StoryPrivacySheet create(String id){
        StoryPrivacySheet sheet=new StoryPrivacySheet();Bundle args=new Bundle();args.putString("id",id);sheet.setArguments(args);return sheet;
    }
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,@Nullable ViewGroup parent,@Nullable Bundle state){
        if(state!=null)selected=state.getString("selected");
        LinearLayout root=new LinearLayout(requireContext());root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(20),dp(20),dp(24));
        TextView heading=new TextView(requireContext());heading.setText("Chỉnh sửa quyền riêng tư");heading.setTextSize(20);root.addView(heading);
        currentLabel=new TextView(requireContext());currentLabel.setPadding(0,dp(12),0,dp(8));root.addView(currentLabel);
        choices=new RadioGroup(requireContext());root.addView(choices);
        for(int n=0;n<VALUES.length;n++){
            MaterialRadioButton option=new MaterialRadioButton(requireContext());option.setId(View.generateViewId());
            option.setTag(VALUES[n]);option.setText(LABELS[n]);option.setMinHeight(dp(48));choices.addView(option);
        }
        choices.setOnCheckedChangeListener((group,id)->{View option=group.findViewById(id);selected=option==null?null:(String)option.getTag();updateSave();});
        status=new TextView(requireContext());status.setPadding(0,dp(8),0,dp(8));root.addView(status);
        save=new MaterialButton(requireContext());save.setText("Lưu");save.setOnClickListener(v->save());root.addView(save);
        reload=new MaterialButton(requireContext());reload.setText("Tải lại quyền hiện tại");reload.setOnClickListener(v->{selected=null;load();});root.addView(reload);
        close=new MaterialButton(requireContext());close.setText("Đóng");close.setOnClickListener(v->dismiss());root.addView(close);
        android.widget.ScrollView scroll=new android.widget.ScrollView(requireContext());scroll.addView(root);return scroll;
    }
    @Override public void onViewCreated(@NonNull View view,@Nullable Bundle state){super.onViewCreated(view,state);load();}
    private void load(){
        if(busy)return;setBusy(true);status.setText("Đang tải quyền hiện tại…");final int token=++generation;
        request=Api.run(()->StoryClient.privacy(requireArguments().getString("id")),new Api.Callback<JSONObject>(){
            public void success(JSONObject result){if(getView()==null||token!=generation)return;
                current=result.optString("audience");allowed.clear();JSONArray values=result.optJSONArray("allowed_audiences");
                for(int n=0;values!=null&&n<values.length();n++){String value=values.optString(n);for(String supported:VALUES)if(supported.equals(value))allowed.add(value);}
                if(allowed.isEmpty()){current=null;setBusy(false);status.setText("Máy chủ chưa cung cấp quyền Story được hỗ trợ. Hãy thử tải lại.");return;}
                currentLabel.setText("Quyền hiện tại: "+label(current));
                if(selected==null||!allowed.contains(selected))selected=current;
                String desired=selected;
                choices.clearCheck();
                for(int n=0;n<choices.getChildCount();n++){
                    MaterialRadioButton option=(MaterialRadioButton)choices.getChildAt(n);
                    if(option.getTag().equals(desired))choices.check(option.getId());
                }
                status.setText("friends".equals(current)?"Quyền cũ hiện chỉ cho chủ tin xem. Chọn quyền mới để thay đổi.":
                        !allowed.contains("public")?"Tài khoản riêng tư chỉ hỗ trợ người theo dõi được phê duyệt hoặc chính bạn.":"");
                setBusy(false);
            }
            public void error(String error){if(getView()==null||token!=generation)return;current=null;setBusy(false);status.setText(error);}
        });
    }
    private void save(){
        if(busy||current==null||!allowed.contains(selected)||selected.equals(current))return;
        final String desired=selected,expected=current;final int token=++generation;
        setBusy(true);status.setText("Đang lưu quyền riêng tư…");
        request=Api.run(()->StoryClient.setAudience(requireArguments().getString("id"),desired,expected),new Api.Callback<Story>(){
            public void success(Story story){
                StoryRings.invalidate();
                if(getView()==null||token!=generation)return;
                confirmed=story.getAudience();current=confirmed;setBusy(false);dismissAllowingStateLoss();
            }
            public void error(String error){if(getView()==null||token!=generation)return;setBusy(false);status.setText(error+"\nChưa xác nhận đổi quyền. Có thể tải lại quyền hiện tại để đối chiếu.");}
        });
    }
    private void setBusy(boolean value){
        busy=value;setCancelable(!value);
        for(int n=0;n<choices.getChildCount();n++){View option=choices.getChildAt(n);option.setEnabled(!value&&allowed.contains(option.getTag()));}
        reload.setEnabled(!value);close.setEnabled(!value);save.setText(value?"Đang xử lý…":"Lưu");updateSave();
    }
    private void updateSave(){if(save!=null)save.setEnabled(!busy&&current!=null&&allowed.contains(selected)&&!current.equals(selected));}
    private String label(String value){for(int n=0;n<VALUES.length;n++)if(VALUES[n].equals(value))return LABELS[n];return "friends".equals(value)?"Chỉ mình tôi (quyền cũ)":"Chưa xác định";}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    @Override public void onSaveInstanceState(@NonNull Bundle out){super.onSaveInstanceState(out);out.putString("selected",selected);}
    @Override public void onDismiss(@NonNull DialogInterface dialog){
        super.onDismiss(dialog);Bundle result=new Bundle();result.putString("story_id",requireArguments().getString("id"));
        if(confirmed!=null)result.putString("audience",confirmed);
        getParentFragmentManager().setFragmentResult(RESULT,result);
    }
    @Override public void onDestroyView(){generation++;if(request!=null){request.remove();request=null;}busy=false;super.onDestroyView();}
}
