package com.example.meetomxh.ui;
import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.*;
import java.util.*;

/** Same normalized composition renderer is used for editing, JPEG export and video overlays. */
public final class StoryCanvasView extends View{
 public interface Listener{void changed();void editText(int index);}
 public final StoryComposition model=new StoryComposition();private Listener listener;private Bitmap photo,overlay;private boolean video,dirty=true,drawing,erase;private int selected=-1,brushColor=Color.WHITE;private float brushWidth=.008f,lastX,lastY,span,angle;private long downAt;private boolean moved;private StoryComposition.Stroke stroke;
 private final Deque<String> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
 public StoryCanvasView(Context c,AttributeSet a){super(c,a);setLayerType(View.LAYER_TYPE_SOFTWARE,null);setClickable(true);setContentDescription("Vùng chỉnh sửa Story: kéo chữ, dùng hai ngón để phóng to và xoay");}
 public void setListener(Listener l){listener=l;}public void setMedia(Bitmap bitmap,boolean isVideo){photo=bitmap;video=isVideo;invalidate();}
 public void checkpoint(){undo.push(model.json());while(undo.size()>25)undo.removeLast();redo.clear();}
 public void changed(){dirty=true;invalidate();if(listener!=null)listener.changed();}
 public void undo(){if(undo.isEmpty())return;redo.push(model.json());model.restore(undo.pop());selected=-1;changed();}
 public void redo(){if(redo.isEmpty())return;undo.push(model.json());model.restore(redo.pop());selected=-1;changed();}
 public void reset(){undo.clear();redo.clear();model.restore("{}");selected=-1;drawing=false;stroke=null;changed();}
 public void brush(boolean enabled,int color,float width,boolean erasing){drawing=enabled;brushColor=color;brushWidth=width;erase=erasing;selected=-1;invalidate();}
 public void clearDrawing(){checkpoint();model.strokes.clear();changed();}
 public void removeText(int index){if(index>=0&&index<model.texts.size()){checkpoint();model.texts.remove(index);selected=-1;changed();}}
 @Override protected void onDraw(Canvas c){c.save();c.scale(getWidth()/720f,getHeight()/1280f);if(video){if(overlay==null)overlay=Bitmap.createBitmap(720,1280,Bitmap.Config.ARGB_8888);if(dirty){overlay.eraseColor(Color.TRANSPARENT);model.drawOverlay(new Canvas(overlay));dirty=false;}c.drawBitmap(overlay,0,0,null);}else model.drawPhoto(c,photo);c.restore();}
 private int hit(float x,float y){for(int i=model.texts.size()-1;i>=0;i--){StoryComposition.TextLayer t=model.texts.get(i);android.text.StaticLayout l=StoryComposition.layout(t);Matrix m=new Matrix();m.postTranslate(-l.getWidth()/2f,-l.getHeight()/2f);m.postScale(t.scale,t.scale);m.postRotate(t.rotation);m.postTranslate(t.x*720,t.y*1280);Matrix inverse=new Matrix();if(m.invert(inverse)){float[] p={x*720,y*1280};inverse.mapPoints(p);if(p[0]>=0&&p[0]<=l.getWidth()&&p[1]>=0&&p[1]<=l.getHeight())return i;}}return -1;}
 @Override public boolean onTouchEvent(MotionEvent e){if(!isEnabled())return false;float x=Math.max(0,Math.min(1,e.getX()/getWidth())),y=Math.max(0,Math.min(1,e.getY()/getHeight()));
  switch(e.getActionMasked()){
   case MotionEvent.ACTION_DOWN:lastX=x;lastY=y;downAt=android.os.SystemClock.elapsedRealtime();moved=false;getParent().requestDisallowInterceptTouchEvent(true);
    if(drawing){if(model.strokes.size()>=200||pointCount()>=8192)return true;checkpoint();stroke=new StoryComposition.Stroke();stroke.color=brushColor;stroke.width=brushWidth;stroke.erase=erase;stroke.points.add(x);stroke.points.add(y);model.strokes.add(stroke);changed();}else{selected=hit(x,y);if(selected>=0)checkpoint();}return true;
   case MotionEvent.ACTION_POINTER_DOWN:if(e.getPointerCount()==2){span=distance(e);angle=angle(e);moved=true;}return true;
   case MotionEvent.ACTION_MOVE:
    if(drawing&&stroke!=null){if(pointCount()<8192){stroke.points.add(x);stroke.points.add(y);changed();}}
    else if(selected>=0&&selected<model.texts.size()){StoryComposition.TextLayer t=model.texts.get(selected);if(e.getPointerCount()==2){float next=distance(e);if(span>0)t.scale=Math.max(.25f,Math.min(4,t.scale*next/span));float a=angle(e);t.rotation+=a-angle;span=next;angle=a;}else{t.x=Math.max(0,Math.min(1,t.x+x-lastX));t.y=Math.max(0,Math.min(1,t.y+y-lastY));if(Math.abs(x-lastX)+Math.abs(y-lastY)>.004)moved=true;}changed();}lastX=x;lastY=y;return true;
   case MotionEvent.ACTION_POINTER_UP:int remaining=e.getActionIndex()==0?1:0;lastX=e.getX(remaining)/getWidth();lastY=e.getY(remaining)/getHeight();return true;
   case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:getParent().requestDisallowInterceptTouchEvent(false);stroke=null;if(e.getActionMasked()==MotionEvent.ACTION_UP&&!drawing&&!moved&&selected>=0&&android.os.SystemClock.elapsedRealtime()-downAt<350&&listener!=null)listener.editText(selected);performClick();return true;
   default:return true;
  }
 }
 private float distance(MotionEvent e){return(float)Math.hypot(e.getX(0)-e.getX(1),e.getY(0)-e.getY(1));}private float angle(MotionEvent e){return(float)Math.toDegrees(Math.atan2(e.getY(1)-e.getY(0),e.getX(1)-e.getX(0)));}
 private int pointCount(){int count=0;for(StoryComposition.Stroke s:model.strokes)count+=s.points.size();return count;}
 @Override public boolean performClick(){super.performClick();return true;}
 public void release(){photo=null;if(overlay!=null){overlay.recycle();overlay=null;}}
}
