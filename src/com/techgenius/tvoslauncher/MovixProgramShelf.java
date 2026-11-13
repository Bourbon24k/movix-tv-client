package com.techgenius.tvosmovix;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** A bounded strip of EPG cards with artwork supplied by the service. */
final class MovixProgramShelf extends LinearLayout {
    private final Activity host;
    private final LinearLayout cards;
    private final TextView details;
    private final HorizontalScrollView stripScroll;
    private int generation;
    private View currentCard;
    private LinearLayout dayControls;
    private TextView dayLabel;
    private int dayOffset;
    private JSONObject selectedChannel;
    private Selection selectAction;
    private static final ExecutorService images=Executors.newFixedThreadPool(2);
    interface Selection { void choose(JSONObject program); }
    MovixProgramShelf(Activity activity) {
        super(activity); host=activity; setOrientation(VERTICAL); setClipChildren(false);
        dayControls=new LinearLayout(activity);dayControls.setGravity(android.view.Gravity.CENTER_VERTICAL);dayControls.setPadding(8,8,8,8);
        TextView previous=text("‹  Предыдущий день",13,Color.WHITE);dayLabel=text("Сегодня",15,Color.WHITE);dayLabel.setGravity(android.view.Gravity.CENTER);TextView nextDay=text("Следующий день  ›",13,Color.WHITE);
        dayControls.addView(previous,new LayoutParams(340,48));dayControls.addView(dayLabel,new LayoutParams(0,48,1));dayControls.addView(nextDay,new LayoutParams(340,48));
        for(TextView button:new TextView[]{previous,nextDay}){button.setGravity(android.view.Gravity.CENTER);button.setFocusable(true);button.setClickable(true);button.setBackground(background(false));button.setOnFocusChangeListener((v,f)->button.setBackground(background(f)));}
        previous.setOnClickListener(v->{if(dayOffset>-7){dayOffset--;reload();}});nextDay.setOnClickListener(v->{if(dayOffset<1){dayOffset++;reload();}});addView(dayControls);
        HorizontalScrollView scroll=new HorizontalScrollView(activity);stripScroll=scroll; scroll.setHorizontalScrollBarEnabled(false); scroll.setClipToPadding(false); scroll.setPadding(7,7,7,7);
        cards=new LinearLayout(activity); cards.setOrientation(HORIZONTAL); cards.setClipChildren(false); scroll.addView(cards); addView(scroll,new LayoutParams(-1,390));
        details=text("",13,0xffc6cddd); details.setMaxLines(1); details.setEllipsize(android.text.TextUtils.TruncateAt.END); details.setPadding(8,4,0,2); addView(details);
    }
    private TextView text(String value,int size,int color) { TextView v=new TextView(host); v.setText(value);v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,size*1.65f);v.setTextColor(color);return v; }
    private GradientDrawable background(boolean focus) { GradientDrawable d=new GradientDrawable();d.setColor(focus?0xf0454d5c:0xd9232833);d.setCornerRadius(20);d.setStroke(focus?3:1,focus?0xfff7faff:0x42ffffff);return d; }
    void load(JSONObject channel, Selection action) {selectedChannel=channel;selectAction=action;dayOffset=0;reload();}
    private void reload(){
        JSONObject channel=selectedChannel;Selection action=selectAction;int seq=++generation;currentCard=null;cards.removeAllViews();details.setText("Загружаем программу…");
        Calendar start=Calendar.getInstance();start.set(Calendar.HOUR_OF_DAY,0);start.set(Calendar.MINUTE,0);start.set(Calendar.SECOND,0);start.set(Calendar.MILLISECOND,0);start.add(Calendar.DATE,dayOffset);long from=start.getTimeInMillis()/1000;start.add(Calendar.DATE,1);long to=start.getTimeInMillis()/1000;
        dayLabel.setText(dayOffset==0?"Сегодня":dayOffset==-1?"Вчера":dayOffset==1?"Завтра":new SimpleDateFormat("d MMMM",new Locale("ru")).format(new Date(from*1000)));
        new Thread(()->{try{JSONArray result=MovixApi.scheduleRows(host.getApplicationContext(),channel.optLong("epg"),from,to);
            host.runOnUiThread(()->{if(seq!=generation||host.isFinishing())return;populate(result,channel,action,seq);if(hasFocus())focusFirst();});
        }catch(Exception ex){host.runOnUiThread(()->{if(seq==generation)details.setText("Не удалось загрузить программу. Попробуйте другой день");});}},"program-cards").start();
    }
    boolean focusDayControls(){if(dayControls.hasFocus())return false;dayControls.getChildAt(0).requestFocus();return true;}
    boolean focusCards(){if(dayControls.hasFocus()){focusFirst();return true;}return false;}
    void focusProgram(long id){for(int i=0;i<cards.getChildCount();i++)if(Long.valueOf(id).equals(cards.getChildAt(i).getTag())){cards.getChildAt(i).requestFocus();return;}focusFirst();}
    void focusFirst() { if(currentCard!=null)currentCard.requestFocus();else if(cards.getChildCount()>0)cards.getChildAt(0).requestFocus(); }
    private void populate(JSONArray data, JSONObject channel, Selection action,int seq) {
        details.setText("");long now=System.currentTimeMillis()/1000;
        SimpleDateFormat fmt=new SimpleDateFormat("HH:mm",Locale.getDefault());
        int startIndex=0;
        for(int p=0;p<data.length();p++){JSONObject item=data.optJSONObject(p);long begin=item.optLong("start");if(begin<=now&&begin+item.optLong("duration")>now){startIndex=Math.max(0,p-3);break;}}
        for(int i=0;i<data.length();i++) {
            JSONObject program=data.optJSONObject(i);long start=program.optLong("start"),end=start+program.optLong("duration");
            
            boolean live=start<=now&&end>now;
            LinearLayout card=new LinearLayout(host);card.setOrientation(VERTICAL);card.setPadding(9,9,9,9);card.setFocusable(true);card.setClickable(true);card.setBackgroundColor(Color.TRANSPARENT);card.setTag(program.optLong("id"));if(live)currentCard=card;
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(440,364);cp.rightMargin=15;cards.addView(card,cp);
            FrameLayout artBox=new FrameLayout(host);artBox.setBackground(background(false));artBox.setPadding(4,4,4,4);ImageView image=new ImageView(host);image.setScaleType(ImageView.ScaleType.CENTER_CROP);artBox.addView(image,new FrameLayout.LayoutParams(-1,-1));
            TextView badge=text(live?"В ЭФИРЕ":fmt.format(new Date(start*1000)),11,Color.WHITE);badge.setPadding(9,4,9,4);badge.setBackgroundColor(live?0xdd266c60:0xcc151922);FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-2,-2);bp.leftMargin=6;bp.topMargin=6;artBox.addView(badge,bp);card.addView(artBox,new LayoutParams(-1,242));
            TextView name=text(program.optString("title"),17,Color.WHITE);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setPadding(2,6,2,0);card.addView(name,new LayoutParams(-1,42));
            TextView time=text(fmt.format(new Date(start*1000))+" — "+fmt.format(new Date(end*1000)),14,0xffbfc8d8);card.addView(time);
            JSONObject meta=program.optJSONObject("program");long poster=0;
            JSONArray resources=meta==null?null:meta.optJSONArray("resources");
            if(resources!=null)for(int j=0;j<resources.length();j++){JSONObject r=resources.optJSONObject(j);String cat=r.optString("category");if(cat.contains("poster")||cat.contains("image")){poster=r.optLong("id");if(poster>0)break;}}
            final boolean channelArt=poster==0; if(poster==0)poster=channel.optLong("poster");final long art=poster;
            image.setScaleType(channelArt?ImageView.ScaleType.FIT_CENTER:ImageView.ScaleType.CENTER_CROP);
            final boolean[] imageRequested={false};
            Runnable fetchArt=()->{if(imageRequested[0])return;imageRequested[0]=true;images.execute(()->{if(seq!=generation||host.isFinishing())return;HttpURLConnection conn=null;try{
                long posterId=art;
                try{JSONObject page=MovixApi.programDetails(host.getApplicationContext(),program.optLong("id"));JSONObject body=page.optJSONObject("data");JSONArray resources2=body==null?null:body.optJSONArray("resources");
                    if(resources2!=null)for(int q=0;q<resources2.length();q++){JSONObject resource=resources2.optJSONObject(q);String type=resource.optString("type");if(type.equals("poster")){posterId=resource.optLong("id");break;}}
                }catch(Exception ignored){}
                if(posterId<=0)return;
                conn=(HttpURLConnection)new URL("https://er-cdn.ertelecom.ru/content/public/r"+posterId+"/480x270:crop").openConnection();conn.setConnectTimeout(5000);conn.setReadTimeout(5000);Bitmap b;try(InputStream in=conn.getInputStream()){b=BitmapFactory.decodeStream(in);}if(b!=null)image.post(()->{if(seq==generation)image.setImageBitmap(b);});
            }catch(Exception ignored){}finally{if(conn!=null)conn.disconnect();}});};
            if(live||i<2||i>=startIndex&&i<startIndex+5)fetchArt.run();
            String description=meta==null||meta.isNull("description")?"":meta.optString("description");
            card.setOnFocusChangeListener((v,has)->{artBox.setBackground(background(has));card.animate().scaleX(has?1.025f:1f).scaleY(has?1.025f:1f).setDuration(140).start();if(has){fetchArt.run();details.setText(description.isEmpty()?program.optString("title"):description);stripScroll.postDelayed(()->{if(card.hasFocus())stripScroll.smoothScrollTo(Math.max(0,card.getLeft()-(stripScroll.getWidth()-card.getWidth())/2),0);},120);}});
            card.setOnClickListener(v->action.choose(program));
        }
        if(cards.getChildCount()==0)details.setText("Программа недоступна");
    }
}
