package com.example.meetomxh.ui;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.util.TypedValue;

/** Compact, swipe-selectable reaction picker using app-drawn icons. */
public final class ReactionBarPopup {
    public interface Listener { void onSelected(String reaction); }
    private static final String[] TYPES={"like","love","care","haha","wow","sad","angry"};
    private static final int[] COLORS={0xff1877f2,0xfff04462,0xffffa928,0xffffbd32,0xffffbd32,0xffffbd32,0xfff45b49};
    private static final String[] LABELS={"Thích","Yêu","Thương","Haha","Wow","Buồn","Phẫn nộ"};
    private final Context context;
    private static final android.util.SparseArray<Bitmap> ICONS=new android.util.SparseArray<>();
    public static Drawable icon(Context context,String type){int index=0;for(int i=0;i<TYPES.length;i++)if(TYPES[i].equals(type)){index=i;break;}Bitmap bitmap;synchronized(ICONS){bitmap=ICONS.get(index);if(bitmap==null){ReactionIcon view=new ReactionIcon(context,index);int size=(int)(48*context.getResources().getDisplayMetrics().density+.5f);view.measure(View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY));view.layout(0,0,size,size);bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));ICONS.put(index,bitmap);}}return new BitmapDrawable(context.getResources(),bitmap);}
    public ReactionBarPopup(Context context){this.context=context;}
    public void show(View anchor, Listener listener){
        LinearLayout row=new LinearLayout(context); row.setGravity(Gravity.CENTER); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(8),dp(5),dp(8),dp(5)); row.setBackgroundResource(com.example.meetomxh.R.drawable.bg_pill_search);
        for(int i=0;i<TYPES.length;i++){
            LinearLayout cell=new LinearLayout(context); cell.setGravity(Gravity.CENTER); cell.setOrientation(LinearLayout.VERTICAL);
            ReactionIcon icon=new ReactionIcon(context,i); cell.addView(icon,new LinearLayout.LayoutParams(dp(40),dp(40)));
            TextView label=new TextView(context); label.setText(LABELS[i]); label.setTextSize(TypedValue.COMPLEX_UNIT_SP,9); label.setTextColor(0xff555b66); label.setGravity(Gravity.CENTER);
            cell.addView(label,new LinearLayout.LayoutParams(dp(42),dp(16)));
            cell.setContentDescription(LABELS[i]); cell.setFocusable(true);
            row.addView(cell,new LinearLayout.LayoutParams(dp(44),dp(58)));
            final int index=i; cell.setOnClickListener(v->select(index,listener));
        }
        PopupWindow popup=new PopupWindow(row,dp(324),dp(70),true); popup.setOutsideTouchable(true); popup.setElevation(dp(12));
        row.setOnTouchListener((v,event)->{
            if(event.getAction()==MotionEvent.ACTION_MOVE||event.getAction()==MotionEvent.ACTION_UP){
                int index=Math.max(0,Math.min(TYPES.length-1,(int)((event.getX()-dp(8))/dp(44))));
                for(int j=0;j<row.getChildCount();j++){View child=row.getChildAt(j);float target=j==index?1.22f:1f;if(child.getScaleX()!=target){child.animate().scaleX(target).scaleY(target).setDuration(90).start();}}
                if(event.getAction()==MotionEvent.ACTION_UP){select(index,listener);popup.dismiss();} return true;
            } return true;
        });
        row.setAlpha(0f); row.setScaleX(.9f); row.setScaleY(.9f);
        popup.setOnDismissListener(()->row.animate().alpha(0f).scaleX(.92f).scaleY(.92f).setDuration(90).start());
        int[] location=new int[2];anchor.getLocationOnScreen(location);
        popup.showAtLocation(anchor.getRootView(),Gravity.TOP|Gravity.CENTER_HORIZONTAL,0,Math.max(dp(8),location[1]-dp(76)));
        row.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).start();
    }
    private void select(int i,Listener listener){if(listener!=null)listener.onSelected(TYPES[i]);}
    private int dp(float n){return (int)(n*context.getResources().getDisplayMetrics().density+.5f);}

    private static final class ReactionIcon extends View {
        private final int index; private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        ReactionIcon(Context c,int i){super(c);index=i;setContentDescription(LABELS[i]);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float d=getResources().getDisplayMetrics().density,s=Math.min(getWidth(),getHeight())/48f;c.save();c.scale(s,s);float cx=24,cy=24;
            if(index==0){p.setColor(COLORS[index]);p.setStyle(Paint.Style.FILL);Path h=new Path();h.moveTo(15,22);h.lineTo(20,22);h.lineTo(25,13);h.quadTo(29,10,29,15);h.lineTo(28,20);h.lineTo(35,20);h.quadTo(39,21,37,25);h.lineTo(34,35);h.quadTo(33,38,29,38);h.lineTo(19,37);h.lineTo(15,34);h.close();c.drawPath(h,p);p.setColor(Color.WHITE);c.drawRect(10,22,15,36,p);}
            else {p.setColor(COLORS[index]);p.setStyle(Paint.Style.FILL);c.drawCircle(cx,cy,18,p);p.setColor(Color.WHITE);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);c.drawCircle(18,20,1.4f,p);c.drawCircle(30,20,1.4f,p);p.setStyle(Paint.Style.FILL);
                if(index==1||index==2){Path h=new Path();h.moveTo(24,33);h.cubicTo(20,30,13,25,16,21);h.cubicTo(18,18,22,19,24,22);h.cubicTo(27,18,32,18,34,22);h.cubicTo(36,26,29,31,24,33);c.drawPath(h,p);if(index==2){p.setColor(0xffffd16a);c.drawCircle(13,31,5,p);c.drawCircle(19,35,5,p);}}
                else {p.setColor(Color.WHITE);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);Path mouth=new Path();if(index==3){mouth.moveTo(17,27);mouth.quadTo(24,39,31,27);}else if(index==4){mouth.addCircle(24,29,4,Path.Direction.CW);}else if(index==5){mouth.moveTo(17,33);mouth.quadTo(24,26,31,33);}else{mouth.moveTo(17,31);mouth.lineTo(22,29);mouth.lineTo(26,31);mouth.lineTo(31,28);}c.drawPath(mouth,p);}
            } c.restore();}
    }
}
