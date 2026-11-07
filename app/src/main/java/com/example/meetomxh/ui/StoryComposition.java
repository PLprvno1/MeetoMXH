package com.example.meetomxh.ui;
import android.graphics.*;
import android.text.*;
import org.json.*;
import java.util.*;

/** Serializable 720x1280 composition. Positions, brush width and font sizes are normalized. */
public final class StoryComposition {
 public static final int WIDTH=720,HEIGHT=1280;
 public static final String[] FILTERS={"Original","Bright","Warm","Cool","Vintage","Black & White","Contrast"};
 public final List<TextLayer> texts=new ArrayList<>();public final List<Stroke> strokes=new ArrayList<>();public int filter,rotation;public boolean crop;
 public static final class TextLayer{public String content="",font="sans-serif";public float x=.5f,y=.5f,scale=1,rotation,size=.055f;public int color=Color.WHITE,alignment=1,background,z;public boolean shadow=true;
  JSONObject json()throws JSONException{return new JSONObject().put("content",content).put("x",x).put("y",y).put("scale",scale).put("rotation",rotation).put("size",size).put("font",font).put("color",color).put("alignment",alignment).put("background",background).put("shadow",shadow).put("z",z);}}
 public static final class Stroke{public int color=Color.WHITE;public float width=.008f;public boolean erase;public final List<Float> points=new ArrayList<>();}
 public String json(){try{JSONObject o=new JSONObject().put("filter",filter).put("rotation",rotation).put("crop",crop);JSONArray t=new JSONArray(),s=new JSONArray();for(TextLayer l:texts)t.put(l.json());for(Stroke l:strokes){JSONArray points=new JSONArray();for(float p:l.points)points.put(p);s.put(new JSONObject().put("color",l.color).put("width",l.width).put("erase",l.erase).put("points",points));}return o.put("texts",t).put("strokes",s).toString();}catch(JSONException e){throw new IllegalStateException(e);}}
 public void restore(String state){try{JSONObject o=new JSONObject(state);filter=o.optInt("filter");rotation=o.optInt("rotation");crop=o.optBoolean("crop");texts.clear();strokes.clear();JSONArray ts=o.optJSONArray("texts"),ss=o.optJSONArray("strokes");
  if(ts!=null)for(int i=0;i<ts.length();i++){JSONObject j=ts.getJSONObject(i);TextLayer l=new TextLayer();l.content=j.optString("content");l.x=(float)j.optDouble("x",.5);l.y=(float)j.optDouble("y",.5);l.scale=(float)j.optDouble("scale",1);l.rotation=(float)j.optDouble("rotation");l.size=(float)j.optDouble("size",.055);l.font=j.optString("font","sans-serif");l.color=j.optInt("color",-1);l.alignment=j.optInt("alignment",1);l.background=j.optInt("background");l.shadow=j.optBoolean("shadow",true);l.z=j.optInt("z",i);texts.add(l);}
  if(ss!=null)for(int i=0;i<ss.length();i++){JSONObject j=ss.getJSONObject(i);Stroke l=new Stroke();l.color=j.optInt("color",-1);l.width=(float)j.optDouble("width",.008);l.erase=j.optBoolean("erase");JSONArray p=j.getJSONArray("points");for(int n=0;n<p.length();n++)l.points.add((float)p.getDouble(n));strokes.add(l);}
 }catch(JSONException error){throw new IllegalArgumentException("Bản nháp không hợp lệ",error);}}
 public static float[] colors(int filter){float[] m={1,0,0,0,0,0,1,0,0,0,0,0,1,0,0,0,0,0,1,0};switch(filter){
  case 1:m[0]=m[6]=m[12]=1.18f;break;case 2:m[0]=1.12f;m[12]=.88f;break;case 3:m[0]=.9f;m[12]=1.13f;break;
  case 4:return new float[]{.6f,.4f,.1f,0,0,.2f,.7f,.1f,0,0,.2f,.35f,.4f,0,0,0,0,0,1,0};
  case 5:return new float[]{.2126f,.7152f,.0722f,0,0,.2126f,.7152f,.0722f,0,0,.2126f,.7152f,.0722f,0,0,0,0,0,1,0};
  case 6:m[0]=m[6]=m[12]=1.25f;m[4]=m[9]=m[14]=-25;break;
 }return m;}
 public static StaticLayout layout(TextLayer l){TextPaint p=new TextPaint(Paint.ANTI_ALIAS_FLAG);p.setTextSize(l.size*WIDTH);p.setColor(l.color);p.setTypeface(Typeface.create(l.font,Typeface.BOLD));if(l.shadow)p.setShadowLayer(3,1,2,0xBB000000);return StaticLayout.Builder.obtain(l.content,0,l.content.length(),p,560).setAlignment(l.alignment==0?Layout.Alignment.ALIGN_NORMAL:l.alignment==2?Layout.Alignment.ALIGN_OPPOSITE:Layout.Alignment.ALIGN_CENTER).setIncludePad(true).build();}
 public void drawOverlay(Canvas c){Paint p=new Paint(3);int save=c.saveLayer(0,0,WIDTH,HEIGHT,null);for(Stroke s:strokes){p.setColor(s.color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(s.width*WIDTH);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setXfermode(s.erase?new PorterDuffXfermode(PorterDuff.Mode.CLEAR):null);Path path=new Path();for(int n=0;n+1<s.points.size();n+=2){float x=s.points.get(n)*WIDTH,y=s.points.get(n+1)*HEIGHT;if(n==0)path.moveTo(x,y);else path.lineTo(x,y);}if(s.points.size()==2){path.lineTo(s.points.get(0)*WIDTH+.1f,s.points.get(1)*HEIGHT+.1f);}c.drawPath(path,p);}c.restoreToCount(save);
  for(TextLayer l:texts){StaticLayout text=layout(l);c.save();c.translate(l.x*WIDTH,l.y*HEIGHT);c.rotate(l.rotation);c.scale(l.scale,l.scale);c.translate(-text.getWidth()/2f,-text.getHeight()/2f);if(l.background!=0){p.setXfermode(null);p.setStyle(Paint.Style.FILL);p.setColor(l.background);c.drawRoundRect(-8,-4,text.getWidth()+8,text.getHeight()+4,12,12,p);}text.draw(c);c.restore();}}
 public void drawPhoto(Canvas c,Bitmap source){c.drawColor(Color.BLACK);if(source!=null){float rotatedW=rotation%180==0?source.getWidth():source.getHeight(),rotatedH=rotation%180==0?source.getHeight():source.getWidth();float ratio=crop?Math.max(WIDTH/rotatedW,HEIGHT/rotatedH):Math.min(WIDTH/rotatedW,HEIGHT/rotatedH);Paint p=new Paint(3);p.setColorFilter(new ColorMatrixColorFilter(colors(filter)));c.save();c.translate(WIDTH/2f,HEIGHT/2f);c.rotate(rotation);c.scale(ratio,ratio);c.drawBitmap(source,-source.getWidth()/2f,-source.getHeight()/2f,p);c.restore();}drawOverlay(c);}
 public Bitmap overlay(){Bitmap b=Bitmap.createBitmap(WIDTH,HEIGHT,Bitmap.Config.ARGB_8888);drawOverlay(new Canvas(b));return b;}
}
