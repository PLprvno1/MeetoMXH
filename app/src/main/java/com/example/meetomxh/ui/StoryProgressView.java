package com.example.meetomxh.ui;
import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
public final class StoryProgressView extends View{
 private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);private int count=1,index;private float progress;
 public StoryProgressView(Context c,AttributeSet a){super(c,a);}
 public void setSegments(int n,int current,float value){count=Math.max(1,n);index=current;progress=Math.max(0,Math.min(1,value));invalidate();setContentDescription("Tin "+(current+1)+" trên "+count);}
 @Override protected void onDraw(Canvas c){float gap=Math.min(4*getResources().getDisplayMetrics().density,getWidth()/(float)(count*4));float width=(getWidth()-gap*(count-1))/count;
  for(int i=0;i<count;i++){float x=i*(width+gap);p.setColor(0x66FFFFFF);c.drawRoundRect(x,0,x+width,getHeight(),3,3,p);p.setColor(Color.WHITE);float f=i<index?1:i==index?progress:0;c.drawRoundRect(x,0,x+width*f,getHeight(),3,3,p);}}
}
