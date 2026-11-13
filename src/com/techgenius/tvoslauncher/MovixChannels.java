package com.techgenius.tvosmovix;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Compact TV shelf: one resume card, a channel rail, and one shared programme caption. */
final class MovixChannels {
    private static final LruCache<Long,Bitmap> images=new LruCache<>(48);
    private static final ExecutorService artwork=Executors.newFixedThreadPool(2);
    private static final ExecutorService guide=Executors.newSingleThreadExecutor();
    static void stopPreview() { }

    private static TextView text(MainActivity a,String value,int size,int color) {
        TextView t=new TextView(a);t.setText(value);t.setTextSize(size);t.setTextColor(color);
        t.setTypeface(Ui.font(a,"medium"));t.setSingleLine(true);t.setEllipsize(android.text.TextUtils.TruncateAt.END);return t;
    }
    private static GradientDrawable glass(MainActivity a,boolean focus,boolean resume) {
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,focus
            ?new int[]{0xe05b677b,0xf02a3343}:resume?new int[]{0xd539455a,0xd51c2535}:new int[]{0xc92a3344,0xd51b2331});
        d.setCornerRadius(a.dp(14));d.setStroke(a.dp(focus?2:0.5f),focus?Color.WHITE:0x40ffffff);return d;
    }
    private static void loadArt(MainActivity a,JSONObject channel,ImageView image) {
        long id=channel.optLong("poster");if(id<=0)return;
        Bitmap cached=images.get(id);if(cached!=null){image.setImageBitmap(cached);return;}
        artwork.execute(()->{HttpURLConnection c=null;try {
            if(a.isDestroyed())return;
            c=(HttpURLConnection)new URL("https://er-cdn.ertelecom.ru/content/public/r"+id+"/240x140:crop").openConnection();c.setConnectTimeout(4000);c.setReadTimeout(4000);
            Bitmap b;try(InputStream in=c.getInputStream()){b=BitmapFactory.decodeStream(in);}
            if(b!=null){images.put(id,b);image.post(()->{if(!a.isDestroyed())image.setImageBitmap(b);});}
        }catch(Exception ignored){}finally{if(c!=null)c.disconnect();}});
    }
    private static void open(MainActivity a,JSONObject channel,boolean resume) {
        long id=channel.optLong("id");
        a.getSharedPreferences("movix_channels",0).edit().putLong("last_channel",id).apply();
        Intent intent;
        if(channel.optLong("hls")>0&&(channel.optBoolean("public")||MovixApi.signedIn(a))) {
            intent=new Intent(a,MovixPlayer.class);intent.putExtra("channel",id);intent.putExtra("resume",resume);
        } else {
            intent=new Intent(Intent.ACTION_VIEW,Uri.parse("https://movix.ru/pages/channel/"+id));
            intent.setClassName("com.ertelecom.domrutvstb","com.ertelecom.domrutvstb.presentation.screen.launcher.LauncherActivity");
        }
        try{a.startActivity(intent);}catch(Exception ignored){}
    }

    private static final class Rail extends LinearLayout {
        final MainActivity host;
        final ArrayList<View> items=new ArrayList<>();
        int selected;
        Rail(MainActivity a){super(a);host=a;setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);setClipChildren(false);setClipToPadding(false);setFocusable(false);}
        @Override public boolean requestFocus(int direction,Rect rect) {
            return !items.isEmpty()?items.get(Math.min(selected,items.size()-1)).requestFocus(direction,rect):super.requestFocus(direction,rect);
        }
        @Override public boolean dispatchKeyEvent(KeyEvent event) {
            int key=event.getKeyCode();
            if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT) {
                if(event.getAction()==KeyEvent.ACTION_DOWN&&!items.isEmpty()) {
                    int next=Math.max(0,Math.min(items.size()-1,selected+(key==KeyEvent.KEYCODE_DPAD_RIGHT?1:-1)));
                    items.get(next).requestFocus();
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
    }
    static View build(MainActivity a) {
        JSONArray channels;
        try(InputStream in=a.getAssets().open("movix_channels.json");ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);channels=new JSONArray(out.toString("UTF-8"));
        }catch(Exception e){return new View(a);}
        if(channels.length()==0)return new View(a);
        JSONObject lastChannel=null;long last=a.getSharedPreferences("movix_channels",0).getLong("last_channel",0);
        for(int i=0;i<channels.length();i++)if(channels.optJSONObject(i).optLong("id")==last){lastChannel=channels.optJSONObject(i);break;}
        final JSONObject recent=lastChannel==null?channels.optJSONObject(0):lastChannel;
        JSONObject saved=null;long savedPosition=0;
        android.content.SharedPreferences resume=a.getSharedPreferences("movix_resume",0);
        if(resume.getLong("channel",0)==recent.optLong("id"))try{saved=new JSONObject(resume.getString("program",""));savedPosition=resume.getLong("position_"+saved.optLong("id"),0);}catch(Exception ignored){}
        final JSONObject savedProgram=saved;

        LinearLayout box=new LinearLayout(a);box.setOrientation(LinearLayout.VERTICAL);box.setClipChildren(false);box.setClipToPadding(false);
        box.setPadding(a.dp(42),a.dp(5),a.dp(42),a.dp(13));
        LinearLayout header=new LinearLayout(a);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text(a,"Телевидение",18,Color.WHITE);title.setTypeface(Ui.font(a,"semibold"));
        header.addView(title,new LinearLayout.LayoutParams(0,a.dp(28),1));
        TextView count=text(a,"MOVIX  ·  "+channels.length()+" каналов",11,0xffd3dce9);count.setLetterSpacing(0.06f);header.addView(count);
        box.addView(header);
        Rail rail=new Rail(a);a.movixList=rail;
        LinearLayout.LayoutParams railLp=new LinearLayout.LayoutParams(-1,a.dp(102));railLp.topMargin=a.dp(3);box.addView(rail,railLp);
        TextView current=text(a,"",13,0xfff2f5fb),next=text(a,"",11,0xffc4cede);
        LinearLayout captions=new LinearLayout(a);captions.setGravity(Gravity.CENTER_VERTICAL);captions.setPadding(a.dp(2),0,a.dp(2),0);
        captions.addView(current,new LinearLayout.LayoutParams(0,a.dp(26),1.25f));
        LinearLayout.LayoutParams nextLp=new LinearLayout.LayoutParams(0,a.dp(26),1);nextLp.leftMargin=a.dp(22);next.setGravity(Gravity.RIGHT);captions.addView(next,nextLp);box.addView(captions);

        FrameLayout continueCard=new FrameLayout(a);continueCard.setPadding(a.dp(14),a.dp(10),a.dp(14),a.dp(10));
        continueCard.setFocusable(true);continueCard.setClickable(true);continueCard.setBackground(glass(a,false,true));
        LinearLayout copy=new LinearLayout(a);copy.setOrientation(LinearLayout.VERTICAL);
        TextView eyebrow=text(a,lastChannel==null?"СМОТРЕТЬ ТВ":savedProgram!=null?"ПРОДОЛЖИТЬ ПРОСМОТР":"ПОСЛЕДНИЙ КАНАЛ",10,0xffc8d5e8);eyebrow.setLetterSpacing(0.07f);copy.addView(eyebrow,new LinearLayout.LayoutParams(-1,a.dp(19)));
        TextView channelName=text(a,recent.optString("name"),16,Color.WHITE);channelName.setTypeface(Ui.font(a,"semibold"));copy.addView(channelName,new LinearLayout.LayoutParams(-1,a.dp(26)));
        TextView programme=text(a,savedProgram==null?"Открыть прямой эфир":savedProgram.optString("title"),11,0xffd4deed);copy.addView(programme,new LinearLayout.LayoutParams(-1,a.dp(21)));
        continueCard.addView(copy,new FrameLayout.LayoutParams(-1,-1));
        if(savedProgram!=null){ProgressBar progress=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);progress.setProgress((int)Math.min(1000,savedPosition/Math.max(1,savedProgram.optLong("duration"))));progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xffdceaff));progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x35ffffff));FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,a.dp(2),Gravity.BOTTOM);continueCard.addView(progress,lp);}
        LinearLayout.LayoutParams continueLp=new LinearLayout.LayoutParams(a.dp(222),a.dp(88));continueLp.rightMargin=a.dp(16);rail.addView(continueCard,continueLp);rail.items.add(continueCard);

        HorizontalScrollView scroll=new HorizontalScrollView(a){
            @Override public boolean executeKeyEvent(KeyEvent event){return false;}
            @Override protected void dispatchDraw(android.graphics.Canvas canvas){
                int save=canvas.save();canvas.clipRect(getScrollX(),getScrollY(),getScrollX()+getWidth(),getScrollY()+getHeight());
                super.dispatchDraw(canvas);canvas.restoreToCount(save);
            }
        };
        scroll.setHorizontalScrollBarEnabled(false);scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);scroll.setClipToPadding(false);scroll.setClipChildren(true);scroll.setFocusable(false);
        LinearLayout cards=new LinearLayout(a);cards.setGravity(Gravity.CENTER_VERTICAL);cards.setClipChildren(false);cards.setClipToPadding(false);cards.setPadding(a.dp(3),a.dp(5),a.dp(8),a.dp(5));scroll.addView(cards);
        rail.addView(scroll,new LinearLayout.LayoutParams(0,-1,1));
        final int[] captionVersion={0};
        java.util.function.BiConsumer<JSONObject,Boolean> caption=(channel,isResume)->{
            int version=++captionVersion[0];
            if(isResume&&savedProgram!=null){current.setText("Продолжить · "+savedProgram.optString("title"));long seconds=resume.getLong("position_"+savedProgram.optLong("id"),0)/1000;next.setText("Остановились на "+(seconds>=3600?String.format(java.util.Locale.getDefault(),"%d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60):String.format(java.util.Locale.getDefault(),"%02d:%02d",seconds/60,seconds%60)));return;}
            current.setText(channel.optString("name"));next.setText("Прямой эфир");
            rail.postDelayed(()->{if(version!=captionVersion[0]||a.isDestroyed())return;guide.execute(()->{try{
                if(version!=captionVersion[0])return;String[] info=MovixApi.programs(a.getApplicationContext(),channel.optLong("epg"));
                rail.post(()->{if(version==captionVersion[0]&&!a.isDestroyed()){if(!info[0].isEmpty())current.setText(info[0]);next.setText(info[1]);}});
            }catch(Exception ignored){}});},220);
        };
        continueCard.setOnFocusChangeListener((v,focus)->{
            continueCard.setBackground(glass(a,focus,true));continueCard.animate().scaleX(focus?1.035f:1f).scaleY(focus?1.035f:1f).setDuration(150).start();
            if(focus){rail.selected=0;caption.accept(recent,true);}
        });
        continueCard.setOnClickListener(v->open(a,recent,true));
        final JSONArray data=channels;final int[] loaded={0};final Runnable[] append={null};
        append[0]=()->{
            int end=Math.min(data.length(),loaded[0]+14);
            while(loaded[0]<end){
                JSONObject channel=data.optJSONObject(loaded[0]++);final int position=rail.items.size();
                LinearLayout card=new LinearLayout(a);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER);card.setPadding(a.dp(9),a.dp(7),a.dp(9),a.dp(5));card.setFocusable(true);card.setClickable(true);card.setBackground(glass(a,false,false));
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(a.dp(112),a.dp(88));lp.rightMargin=a.dp(9);cards.addView(card,lp);rail.items.add(card);
                LinearLayout logoRow=new LinearLayout(a);logoRow.setGravity(Gravity.CENTER_VERTICAL);
                ImageView logo=new ImageView(a);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);logoRow.addView(logo,new LinearLayout.LayoutParams(0,a.dp(42),1));
                TextView number=text(a,channel.optInt("number")>0?String.format(java.util.Locale.getDefault(),"%02d",channel.optInt("number")):"ТВ",10,0xffc3cedf);number.setGravity(Gravity.TOP|Gravity.RIGHT);logoRow.addView(number,new LinearLayout.LayoutParams(a.dp(22),a.dp(40)));card.addView(logoRow,new LinearLayout.LayoutParams(-1,a.dp(48)));
                TextView name=text(a,channel.optString("name"),12,Color.WHITE);name.setGravity(Gravity.CENTER);card.addView(name,new LinearLayout.LayoutParams(-1,a.dp(25)));
                card.setOnFocusChangeListener((v,focus)->{
                    card.setBackground(glass(a,focus,false));card.animate().scaleX(focus?1.045f:1f).scaleY(focus?1.045f:1f).setDuration(150).start();
                    if(focus){rail.selected=position;caption.accept(channel,false);if(position>=rail.items.size()-3&&loaded[0]<data.length())append[0].run();scroll.post(()->{int left=card.getLeft(),right=card.getRight(),x=scroll.getScrollX();if(left<x+a.dp(5))scroll.smoothScrollTo(Math.max(0,left-a.dp(5)),0);else if(right>x+scroll.getWidth()-a.dp(8))scroll.smoothScrollTo(right-scroll.getWidth()+a.dp(8),0);});}
                });
                card.setOnClickListener(v->open(a,channel,false));loadArt(a,channel,logo);
            }
        };
        append[0].run();caption.accept(recent,true);
        return box;
    }
}
