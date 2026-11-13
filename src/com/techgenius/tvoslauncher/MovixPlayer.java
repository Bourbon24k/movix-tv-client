package com.techgenius.tvosmovix;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.net.Uri;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Movix live and official catch-up playback. */
public class MovixPlayer extends Activity {
    private MovixPlayback video;
    private TextView title, now, next, hint, loading, pauseButton, favoriteButton;
    private LinearLayout overlay;
    private LinearLayout topPanel;
    private TextView controlCaption, elapsedLabel, endLabel;
    private MovixTimeline timeline;
    private android.app.Dialog channelPicker;
    private android.app.Dialog resumeDialog;
    static MovixPlayer active;
    private boolean foreground=true, desiredPlay=true;
    private android.media.session.MediaSession mediaSession;
    private long liveClockCorrection,lastChannelPress;
    private final StringBuilder channelDigits=new StringBuilder();
    private final Handler channelInputHandler=new Handler();
    private final Runnable commitChannelDigits=()->commitChannelNumber();
    private int previousChannel=-1,lastKeyCode;
    private String keyFocus="";
    private long lastProgramRefresh;
    private JSONArray schedule=new JSONArray();
    private android.widget.ImageView channelLogo;
    private TextView topChannel;
    private android.widget.TextClock clock;
    private boolean swallowCenterUp;
    private boolean resumeAfterBackground, playbackError, seeking;
    private Boolean iconPlaying;
    private boolean archiveMode, archiveLoading, pendingPlay=true;
    private long pendingPosition=-1,lastSavedAt;
    private JSONObject archiveProgram;
    private void savePlayback(){
        if(video==null||archiveLoading||pendingPosition>=0||index<0||(!video.ready()&&!video.ended()))return;
        JSONObject savedProgram=archiveMode?archiveProgram:currentProgram;
        if(savedProgram==null||(!archiveMode&&(!MovixApi.signedIn(this)||!channels.optJSONObject(index).optBoolean("catchup"))))return;
        long duration=savedProgram.optLong("duration")*1000,pos=archiveMode?video.position():currentProgramPosition();
        if(duration>0)pos=Math.min(pos,duration);if(video.ended())pos=0;
        getSharedPreferences("movix_resume",0).edit().putLong("position_"+savedProgram.optLong("id"),pos)
            .putLong("channel",channels.optJSONObject(index).optLong("id")).putString("program",savedProgram.toString()).putBoolean("paused",!desiredPlay).putLong("updated",System.currentTimeMillis()).apply();
    }
    private void openArchive(JSONObject program,long position,boolean playWhenReady){
        if(index<0||program==null)return;
        if(program.optLong("start")>System.currentTimeMillis()/1000){notice("Передача ещё не началась");return;}
        if(!channels.optJSONObject(index).optBoolean("catchup")){notice("Оператор не предоставляет архив этого канала");return;}
        savePlayback();seeking=false;timeline.cancel();ui.removeCallbacks(applySeek);int seq=++generation;
        boolean previousPlay=video.wantsToPlay();
        archiveLoading=true;pendingPosition=Math.max(0,position);pendingPlay=playWhenReady;desiredPlay=playWhenReady;
        loading.setText("Загружаем запись передачи…");loading.setVisibility(View.VISIBLE);video.pause();
        JSONObject ch=channels.optJSONObject(index);loadChannelLogo(ch,seq);
        new Thread(()->{try{
            String url=MovixApi.archive(getApplicationContext(),ch,program);
            runOnUiThread(()->{if(seq!=generation||isFinishing())return;archiveMode=true;archiveProgram=program;archiveLoading=false;playbackError=false;
                now.setText(program.optString("title"));showFollowingProgramme(program);setGuideExpanded(false);video.setVideoURI(Uri.parse(url),desiredPlay&&foreground);});
        }catch(Exception ex){runOnUiThread(()->{if(seq!=generation)return;archiveLoading=false;pendingPosition=-1;loading.setVisibility(View.GONE);desiredPlay=previousPlay&&playWhenReady;if(desiredPlay&&foreground)video.start();
            notice(ex instanceof MovixApi.Failure?ex.getMessage():"Не удалось загрузить запись. Проверьте подключение и повторите");showInfo();});}},"movix-archive").start();
    }
    private void showFollowingProgramme(JSONObject programme){
        long end=programme.optLong("start")+programme.optLong("duration");JSONObject following=null;
        for(int i=0;i<schedule.length();i++){JSONObject p=schedule.optJSONObject(i);long start=p.optLong("start");if(start>=end-2&&start<end+10800&&(following==null||start<following.optLong("start")))following=p;}
        if(following!=null)next.setText("Далее "+clockTime(following.optLong("start")*1000).substring(0,5)+" · "+following.optString("title"));
        else next.setText("Передача до "+clockTime(end*1000).substring(0,5));
    }
    private void chooseProgram(JSONObject program){long pos=getSharedPreferences("movix_resume",0).getLong("position_"+program.optLong("id"),0);openArchive(program,pos,true);}
    private void maybePromptSavedResume() {
        if ((resumeDialog!=null&&resumeDialog.isShowing())||index<0) return;
        JSONObject channel=channels.optJSONObject(index);if(channel==null||!channel.optBoolean("catchup"))return;
        android.content.SharedPreferences prefs=getSharedPreferences("movix_resume",0);
        if(prefs.getLong("channel",0)!=channel.optLong("id"))return;
        String encoded=prefs.getString("program","");if(encoded.isEmpty())return;
        try {
            JSONObject program=new JSONObject(encoded);long id=program.optLong("id"),position=prefs.getLong("position_"+id,0),duration=program.optLong("duration")*1000;
            if(id<=0||position<10_000||(duration>0&&position>=duration-5_000))return;
            desiredPlay=false;ui.removeCallbacks(playbackTick);if(video!=null)video.pause();
            showResumeChoice(program,position);
        } catch(Exception ignored) { }
    }
    private void finishResumePrompt(boolean live) {
        desiredPlay=true;ui.removeCallbacks(playbackTick);ui.post(playbackTick);
        if(live)returnToLive();
    }
    private void showResumeChoice(JSONObject program,long position) {
        android.app.Dialog dialog=new android.app.Dialog(this);resumeDialog=dialog;dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);dialog.setCancelable(true);
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(34,28,34,30);box.setBackground(MovixChannelPicker.background(false));
        TextView heading=MovixChannelPicker.text(this,"Продолжить просмотр?",22);box.addView(heading,new LinearLayout.LayoutParams(-1,64));
        TextView detail=MovixChannelPicker.text(this,program.optString("title")+"\nОстановились на "+formatDuration(position),14);detail.setTextColor(0xffc6d2e4);detail.setSingleLine(false);detail.setMaxLines(2);box.addView(detail,new LinearLayout.LayoutParams(-1,76));
        TextView continueButton=MovixChannelPicker.text(this,"Продолжить с последнего места",16);TextView liveButton=MovixChannelPicker.text(this,"Вернуться в прямой эфир",16);
        for(TextView button:new TextView[]{continueButton,liveButton}){button.setGravity(Gravity.CENTER);button.setFocusable(true);button.setClickable(true);button.setBackground(MovixChannelPicker.background(false));box.addView(button,new LinearLayout.LayoutParams(-1,70));}
        ((LinearLayout.LayoutParams)liveButton.getLayoutParams()).topMargin=10;
        View.OnFocusChangeListener focus=(v,has)->{TextView t=(TextView)v;t.setBackground(MovixChannelPicker.background(has));t.setTextColor(has?0xff172131:Color.WHITE);};continueButton.setOnFocusChangeListener(focus);liveButton.setOnFocusChangeListener(focus);
        continueButton.setOnClickListener(v->{dialog.dismiss();resumeDialog=null;finishResumePrompt(false);openArchive(program,position,true);});
        liveButton.setOnClickListener(v->{dialog.dismiss();resumeDialog=null;finishResumePrompt(true);showInfo();});
        dialog.setOnCancelListener(d->{resumeDialog=null;finishResumePrompt(true);showInfo();});dialog.setContentView(box);
        Window w=dialog.getWindow();if(w!=null){w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.setDimAmount(.48f);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setGravity(Gravity.CENTER);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);}
        dialog.show();if(w!=null){w.setLayout(760,-2);w.getDecorView().setSystemUiVisibility(5894);}continueButton.requestFocus();
    }
    private long currentProgramPosition(){long time=video.windowStart()>0?video.windowStart()+video.position()+liveClockCorrection:System.currentTimeMillis();return currentProgram==null?0:Math.max(0,Math.min(currentProgram.optLong("duration")*1000,time-currentProgram.optLong("start")*1000));}
    private void notice(String text){android.widget.Toast.makeText(this,text,android.widget.Toast.LENGTH_LONG).show();}
    private long transportLength(){
        JSONObject programme=archiveMode?archiveProgram:currentProgram;
        if(pendingPosition>=0&&programme!=null)return Math.max(0,Math.min(programme.optLong("duration")*1000,System.currentTimeMillis()-programme.optLong("start")*1000));
        if(programme!=null&&(archiveMode||channels.optJSONObject(index).optBoolean("catchup"))){long elapsed=Math.max(0,System.currentTimeMillis()-programme.optLong("start")*1000);long declared=programme.optLong("duration")*1000;if(elapsed<declared)return archiveMode?Math.min(video.duration(),elapsed):elapsed;}
        return !archiveMode&&currentProgram!=null&&channels.optJSONObject(index).optBoolean("catchup")?currentProgram.optLong("duration")*1000:video.duration();
    }
    private long displayLength(){JSONObject p=archiveMode?archiveProgram:currentProgram;return p!=null&&p.optLong("duration")>0?p.optLong("duration")*1000:video.duration();}
    private long transportPosition(){if(pendingPosition>=0)return pendingPosition;return !archiveMode&&currentProgram!=null&&channels.optJSONObject(index).optBoolean("catchup")?currentProgramPosition():video.position();}
    private void commitSeek(long target){
        target=Math.max(0,Math.min(Math.max(0,transportLength()-800),target));
        if(!archiveMode&&currentProgram!=null&&channels.optJSONObject(index).optBoolean("catchup"))openArchive(currentProgram,target,desiredPlay);
        else if(video.seekable()){video.seekTo(target);savePlayback();showInfo();}else notice("Перемотка этого канала недоступна");
    }
    private void refreshProgramme(){
        if(archiveMode||index<0||archiveLoading)return;long nowSeconds=System.currentTimeMillis()/1000;JSONObject current=null,following=null;
        for(int i=0;i<schedule.length();i++){JSONObject p=schedule.optJSONObject(i);long start=p.optLong("start");if(start<=nowSeconds&&start+p.optLong("duration")>nowSeconds)current=p;else if(start>nowSeconds&&(following==null||start<following.optLong("start")))following=p;}
        currentProgram=current;if(current!=null){now.setText(current.optString("title"));programStart=current.optLong("start")*1000;programEnd=programStart+current.optLong("duration")*1000;}
        if(following!=null)next.setText("Далее "+clockTime(following.optLong("start")*1000).substring(0,5)+" · "+following.optString("title"));
    }


    private long seekTarget, programStart, programEnd;
    private TextView rewindButton, forwardButton, startoverButton, liveButton, playbackStatus;
    private final Runnable playbackTick=new Runnable(){public void run(){updatePlaybackUi();ui.postDelayed(this,500);}};
    private final Runnable applySeek=()->{if(video!=null&&seeking){commitSeek(seekTarget);seeking=false;updatePlaybackUi();}};
    private MovixProgramShelf programShelf;
    private boolean guideExpanded;
    private JSONObject currentProgram;
    private final java.util.ArrayList<TextView> playerButtons=new java.util.ArrayList<>();
    private LinearLayout navigationRow, controlsRow;
    private JSONArray channels = new JSONArray();
    private int index = -1, generation = 0;
    private final Handler ui = new Handler();
    private final Runnable hide = () -> { if (video != null && video.isPlaying() && !guideExpanded && !timeline.editing() && (channelPicker==null||!channelPicker.isShowing())) { overlay.setVisibility(View.GONE); if(topPanel!=null)topPanel.setVisibility(View.GONE); video.requestFocus(); } };
    private void showInfo() { overlay.setVisibility(View.VISIBLE); if(topPanel!=null)topPanel.setVisibility(View.VISIBLE); ui.removeCallbacks(hide); ui.postDelayed(hide, 5500); }
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(0xff000000);
        video = new MovixPlayback(this); root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        loading = new TextView(this); loading.setTextColor(-1); loading.setTextSize(20); loading.setGravity(Gravity.CENTER); loading.setText("Загружаем канал…");
        root.addView(loading, new FrameLayout.LayoutParams(-1, -1));
        topPanel=new LinearLayout(this); topPanel.setOrientation(LinearLayout.HORIZONTAL);topPanel.setGravity(Gravity.CENTER_VERTICAL);topPanel.setPadding(90,50,90,24);
        topPanel.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xd90d111b,0x000d111b}));
        channelLogo=new android.widget.ImageView(this);channelLogo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        topPanel.addView(channelLogo,new LinearLayout.LayoutParams(148,102));
        topChannel=label(15,0xffe8edf7);LinearLayout.LayoutParams channelNameLp=new LinearLayout.LayoutParams(280,-2);channelNameLp.leftMargin=20;topPanel.addView(topChannel,channelNameLp);
        TextView categories=label(17,0xffffffff);categories.setText("▦  Каналы и категории");categories.setGravity(Gravity.CENTER);categories.setFocusable(true);categories.setClickable(true);categories.setOnClickListener(v->showChannels());
        categories.setOnFocusChangeListener((v,has)->{categories.setTextColor(has?0xff101620:0xffffffff);categories.setBackground(has?glassBackground(true,22):null);ui.removeCallbacks(hide);});
        topPanel.addView(categories,new LinearLayout.LayoutParams(0,70,1f));
        clock=new android.widget.TextClock(this);clock.setFormat24Hour("HH:mm");clock.setFormat12Hour("HH:mm");clock.setTextColor(0xffffffff);clock.setTextSize(21);clock.setGravity(Gravity.RIGHT);
        topPanel.addView(clock,new LinearLayout.LayoutParams(330,70));
        root.addView(topPanel,new FrameLayout.LayoutParams(-1,214,Gravity.TOP));
        overlay = new LinearLayout(this); overlay.setOrientation(LinearLayout.VERTICAL); overlay.setPadding(90,20,90,32);
        overlay.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x000b1018,0xe60e141f,0xf5151c28}));
        title = label(15, 0xffbdc8d9); now = label(24, 0xffffffff); next = label(14, 0xffbdc8d9); hint = label(11, 0xffa9b6ca);
        hint.setText("OK — управление       ↓ — программа передач       Назад — скрыть панель");
        LinearLayout controls = new LinearLayout(this); controls.setOrientation(LinearLayout.HORIZONTAL); controls.setGravity(Gravity.CENTER); controls.setPadding(0,8,0,0);
        controlsRow=controls;
        favoriteButton=addNativeIcon(controls,R.drawable.native_favorite,"В избранное",this::toggleFavorite);
        startoverButton=addNativeIcon(controls,R.drawable.native_startover,"С начала передачи",this::restartProgram);
        rewindButton=addNativeIcon(controls,R.drawable.native_rewind,"Назад на 10 секунд",()->seekBy(-10000));
        pauseButton=addNativeIcon(controls,R.drawable.native_pause,"Пауза",this::togglePause);
        forwardButton=addNativeIcon(controls,R.drawable.native_forward,"Вперёд на 10 секунд",()->seekBy(10000));
        liveButton=addNativeIcon(controls,R.drawable.native_live,"К прямому эфиру",this::returnToLive);
        addNativeIcon(controls,R.drawable.native_settings,"Настройки",this::showPlayerSettings);
        overlay.addView(controls,new LinearLayout.LayoutParams(-1,110));
        controlCaption=label(14,0xffe5ebf5);controlCaption.setGravity(Gravity.CENTER);controlCaption.setText("Пауза");overlay.addView(controlCaption,new LinearLayout.LayoutParams(-1,44));
        LinearLayout information=new LinearLayout(this);information.setOrientation(LinearLayout.HORIZONTAL);information.setGravity(Gravity.BOTTOM);
        LinearLayout playing=new LinearLayout(this);playing.setOrientation(LinearLayout.VERTICAL);
        title.setVisibility(View.GONE);now.setTextSize(23);next.setTextSize(16);next.setMaxLines(2);next.setSingleLine(false);
        playing.addView(now,new LinearLayout.LayoutParams(-1,62));
        LinearLayout timeRow=new LinearLayout(this);timeRow.setGravity(Gravity.CENTER_VERTICAL);timeRow.setPadding(0,14,0,8);
        elapsedLabel=label(14,0xffe0e7f2);timeRow.addView(elapsedLabel,new LinearLayout.LayoutParams(140,40));
        timeline=new MovixTimeline(this);timeline.commit=this::commitSeek;timeline.setOnFocusChangeListener((v,has)->{ui.removeCallbacks(hide);hint.setText(has?"← →  Выбрать время     OK  Перемотать     Назад  Отменить":"↓  Шкала времени и программа передач");});
        timeRow.addView(timeline,new LinearLayout.LayoutParams(0,60,1f));endLabel=label(14,0xffe0e7f2);endLabel.setGravity(Gravity.RIGHT);timeRow.addView(endLabel,new LinearLayout.LayoutParams(140,40));playing.addView(timeRow);
        information.addView(playing,new LinearLayout.LayoutParams(0,156,3f));LinearLayout.LayoutParams nextLp=new LinearLayout.LayoutParams(0,126,2f);nextLp.leftMargin=32;information.addView(next,nextLp);overlay.addView(information);
        navigationRow=new LinearLayout(this);navigationRow.setGravity(Gravity.CENTER);navigationRow.setPadding(0,10,0,0);
        addButton(navigationRow,"ТВ-гид",()->setGuideExpanded(!guideExpanded));
        addButton(navigationRow,"Каналы и категории",this::showChannels);
        overlay.addView(navigationRow,new LinearLayout.LayoutParams(-1,82));
        programShelf=new MovixProgramShelf(this);programShelf.setVisibility(View.GONE);overlay.addView(programShelf,new LinearLayout.LayoutParams(-1,-2));
        playbackStatus=label(12,0xffd6e0ef);playbackStatus.setGravity(Gravity.CENTER);overlay.addView(playbackStatus,new LinearLayout.LayoutParams(-1,32));
        hint.setText("↓  Программа передач");hint.setGravity(Gravity.CENTER);overlay.addView(hint,new LinearLayout.LayoutParams(-1,30));
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)); setContentView(root);
        try (InputStream in=getAssets().open("movix_channels.json"); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] bbuf=new byte[8192]; int n; while ((n=in.read(bbuf))!=-1) out.write(bbuf,0,n); channels=new JSONArray(out.toString("UTF-8"));
        } catch (Exception ignored) { }
        JSONObject firstChannel=channels.optJSONObject(0);
        long defaultChannel=firstChannel==null?0:firstChannel.optLong("id");
        long id = b!=null?b.getLong("channel",defaultChannel):getIntent().getLongExtra("channel", getSharedPreferences("movix_channels",0).getLong("last_channel",defaultChannel));
        int channelNumber=getIntent().getIntExtra("channel_number",0);if(channelNumber>0)for(int q=0;q<channels.length();q++)if(channels.optJSONObject(q).optInt("number")==channelNumber){id=channels.optJSONObject(q).optLong("id");break;}
        for (int i=0; i<channels.length(); i++) if (channels.optJSONObject(i).optLong("id") == id) { index=i; break; }
        if (index < 0) { loading.setText("Канал не найден"); return; }
        video.onReady=()->{loading.setVisibility(View.GONE);if(!archiveMode&&video.windowStart()>0){long drift=System.currentTimeMillis()-(video.windowStart()+video.duration());liveClockCorrection=Math.abs(drift)>120000?Math.round(drift/900000d)*900000L:0;android.util.Log.i("MovixPlayback","CLOCK_CORRECTION "+liveClockCorrection);}if(pendingPosition>=0){long target=pendingPosition;pendingPosition=-1;video.seekTo(target);}if(!desiredPlay||!foreground)video.pause();updatePlaybackUi();if(foreground){pauseButton.requestFocus();showInfo();}};
        video.onChanged=this::updatePlaybackUi;
        video.onError=message->{
            if(archiveMode&&archiveProgram!=null&&desiredPlay&&archiveProgram.optLong("start")*1000+archiveProgram.optLong("duration")*1000>System.currentTimeMillis()&&video.position()+7000>=System.currentTimeMillis()-archiveProgram.optLong("start")*1000){returnToLive();return;}

            if(message.equals("ERROR_CODE_BEHIND_LIVE_WINDOW")&&!archiveMode){if(currentProgram!=null&&channels.optJSONObject(index).optBoolean("catchup"))openArchive(currentProgram,currentProgramPosition(),desiredPlay);else if(desiredPlay)video.recoverLive();else{playbackError=true;loading.setText("Пауза вышла за доступный эфир. OK — вернуться к просмотру");loading.setVisibility(View.VISIBLE);}return;}
            playbackError=true;loading.setText("Не удалось воспроизвести поток\nOK — повторить");loading.setVisibility(View.VISIBLE);showInfo();
        };
        setupMediaSession();play(index);
        try {
            if(b!=null&&!b.getString("program","").isEmpty()){openArchive(new JSONObject(b.getString("program")),b.getLong("position"),b.getBoolean("playing",true));return;}
            String supplied=getIntent().getStringExtra("program");
            if(supplied!=null)chooseProgram(new JSONObject(supplied));
            else maybePromptSavedResume();
        }catch(Exception ignored){}
    }
    private android.graphics.drawable.GradientDrawable glassBackground(boolean focused,int radius) {
        android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,focused?new int[]{0xfff8fbff,0xffdce4f2}:new int[]{0xa6535f73,0x9c202a3a});
        d.setCornerRadius(radius);d.setStroke(1,focused?0xffffffff:0x77dce7ff);return d;
    }
    private TextView addNativeIcon(LinearLayout row,int resource,String caption,Runnable action) {
        TextView v=label(1,0xffffffff);v.setText("");v.setGravity(Gravity.CENTER);v.setFocusable(true);v.setClickable(true);v.setContentDescription(caption);v.setBackground(glassBackground(false,48));
        setNativeIcon(v,resource);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(96,96);lp.leftMargin=12;lp.rightMargin=12;row.addView(v,lp);playerButtons.add(v);
        v.setOnFocusChangeListener((view,focused)->{v.setBackground(glassBackground(focused,48));tintNativeIcon(v,focused);v.animate().scaleX(focused?1.06f:1f).scaleY(focused?1.06f:1f).setDuration(130).start();if(focused&&controlCaption!=null)controlCaption.setText(caption);ui.removeCallbacks(hide);});
        v.setOnClickListener(view->action.run());return v;
    }
    private void setNativeIcon(TextView view,int resource) {
        android.graphics.drawable.Drawable icon=getDrawable(resource).mutate();icon.setBounds(0,0,64,64);icon.setTint(view.hasFocus()?0xff131d2b:0xfff5f8ff);
        view.setCompoundDrawables(null,icon,null,null);view.setPadding(16,16,16,16);
    }
    private void tintNativeIcon(TextView view,boolean focused) { android.graphics.drawable.Drawable d=view.getCompoundDrawables()[1];if(d!=null)d.setTint(focused?0xff131d2b:0xfff5f8ff); }
    private void setGuideExpanded(boolean expanded) {
        guideExpanded=expanded;programShelf.setVisibility(expanded?View.VISIBLE:View.GONE);
        controlsRow.setVisibility(expanded?View.GONE:View.VISIBLE);controlCaption.setVisibility(expanded?View.GONE:View.VISIBLE);navigationRow.setVisibility(expanded?View.GONE:View.VISIBLE);
        hint.setText(expanded?"↑  Управление     ← →  Выбор передачи":"↓  Программа передач");ui.removeCallbacks(hide);
        if(expanded){programShelf.requestFocus();programShelf.post(()->{if(archiveMode&&archiveProgram!=null)programShelf.focusProgram(archiveProgram.optLong("id"));else programShelf.focusFirst();});}else pauseButton.requestFocus();
    }
    private void openCurrentProgram() { if(currentProgram!=null)chooseProgram(currentProgram); }
    private void openNativeProgram(JSONObject program) { chooseProgram(program); }
    private void seekBy(int delta) {
        if(archiveLoading){pendingPosition=Math.max(0,pendingPosition+delta);return;}
        long from=seeking?seekTarget:transportPosition();seekTarget=Math.max(0,Math.min(Math.max(0,transportLength()-800),from+delta));seeking=true;
        showInfo();ui.removeCallbacks(applySeek);ui.postDelayed(applySeek,300);timeline.requestFocus();
    }
    private static String formatDuration(long millis){long sec=Math.max(0,millis/1000);return sec>=3600?String.format(java.util.Locale.getDefault(),"%d:%02d:%02d",sec/3600,(sec/60)%60,sec%60):String.format(java.util.Locale.getDefault(),"%02d:%02d",sec/60,sec%60);}
    private String clockTime(long millis){return new java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.getDefault()).format(new java.util.Date(millis));}
    private void restartProgram(){
        if(archiveMode&&archiveProgram!=null){desiredPlay=true;video.seekTo(0);video.start();showInfo();}
        else if(currentProgram!=null)openArchive(currentProgram,0,true);
    }
    private void returnToLive(){
        savePlayback();if(archiveMode||archiveLoading)play(index);
        else if(video.live()){seeking=false;ui.removeCallbacks(applySeek);desiredPlay=true;video.goLive();showInfo();}
        else play(index);
    }
    private void updatePlaybackUi(){
        if(video==null||playbackStatus==null)return;
        if(System.currentTimeMillis()-lastProgramRefresh>1000){refreshProgramme();lastProgramRefresh=System.currentTimeMillis();}
        boolean playing=archiveLoading?desiredPlay:video.wantsToPlay()&&!video.ended();
        if(System.currentTimeMillis()-lastSavedAt>3000){savePlayback();lastSavedAt=System.currentTimeMillis();}
        if(video.ready()&&!archiveLoading&&!playbackError)loading.setVisibility(View.GONE);
        if(iconPlaying==null||iconPlaying!=playing){setNativeIcon(pauseButton,playing?R.drawable.native_pause:R.drawable.native_play);iconPlaying=playing;}
        pauseButton.setContentDescription(playing?"Пауза":"Продолжить");if(pauseButton.hasFocus())controlCaption.setText(playing?"Пауза":"Продолжить просмотр");
        long length=displayLength(),available=transportLength(),position=seeking?seekTarget:transportPosition();
        long start=archiveMode&&archiveProgram!=null?archiveProgram.optLong("start")*1000:currentProgram!=null?currentProgram.optLong("start")*1000:video.windowStart();
        timeline.model(position,length,start,available);elapsedLabel.setText(start>0?clockTime(start).substring(0,5):formatDuration(0));endLabel.setText(start>0?clockTime(start+length).substring(0,5):formatDuration(length));
        rewindButton.setAlpha(position>1200?1f:.35f);forwardButton.setAlpha(available-position>1500?1f:.35f);liveButton.setAlpha(archiveMode||!playing||video.liveOffset()>6000?1f:.45f);
        if(timeline.editing())playbackStatus.setText("← → Выбрать время · OK Перемотать · Назад Отменить");
        else if(playbackError)playbackStatus.setText("Ошибка воспроизведения · OK — повторить");
        else if(archiveLoading||video.loading())playbackStatus.setText("Загрузка…");
        else if(video.ended())playbackStatus.setText("Передача закончилась · Выберите следующую в ТВ-гиде");
        else if(!playing)playbackStatus.setText("На паузе · "+(start>0?clockTime(start+position):formatDuration(position)));
        else if(archiveMode)playbackStatus.setText("АРХИВ · "+formatDuration(position)+" / "+formatDuration(length));
        else {long delay=video.windowStart()>0?Math.max(0,System.currentTimeMillis()-video.windowStart()-video.position()-liveClockCorrection):0;playbackStatus.setText(delay>30000?"Сдвиг эфира · −"+formatDuration(delay):"● ПРЯМОЙ ЭФИР");}
        if(mediaSession!=null){long actions=android.media.session.PlaybackState.ACTION_PLAY|android.media.session.PlaybackState.ACTION_PAUSE|android.media.session.PlaybackState.ACTION_PLAY_PAUSE|android.media.session.PlaybackState.ACTION_SEEK_TO|android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT|android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS|android.media.session.PlaybackState.ACTION_STOP|android.media.session.PlaybackState.ACTION_FAST_FORWARD|android.media.session.PlaybackState.ACTION_REWIND;mediaSession.setPlaybackState(new android.media.session.PlaybackState.Builder().setActions(actions).setState(playing?android.media.session.PlaybackState.STATE_PLAYING:android.media.session.PlaybackState.STATE_PAUSED,position,playing?1f:0f).build());}
    }
    private void showPlayerSettings() {
        ui.removeCallbacks(hide);
        MovixChannelPicker.options(this,"Плеер",new String[]{"Звуковая дорожка","Субтитры","С начала передачи","Прямой эфир","Программа передач"},which->{
            if(which==0||which==1){int type=which==0?androidx.media3.common.C.TRACK_TYPE_AUDIO:androidx.media3.common.C.TRACK_TYPE_TEXT;String[] tracks=video.trackLabels(type);if(tracks.length==0){notice(which==0?"У этого потока нет дополнительных звуковых дорожек":"Оператор не передаёт субтитры в этом потоке");return;}String[] options=new String[tracks.length+1];options[0]=which==0?"Автоматически":"Выключены";System.arraycopy(tracks,0,options,1,tracks.length);MovixChannelPicker.options(this,which==0?"Звук":"Субтитры",options,item->video.selectTrack(type,item==0?(type==androidx.media3.common.C.TRACK_TYPE_TEXT?-2:-1):item-1));}
            else if(which==2)restartProgram();else if(which==3)returnToLive();else setGuideExpanded(true);
        });
    }
    private void loadChannelLogo(JSONObject ch,int seq) {
        channelLogo.setImageDrawable(null);long art=ch.optLong("poster");if(art==0)return;
        new Thread(()->{java.net.HttpURLConnection c=null;try{
            c=(java.net.HttpURLConnection)new java.net.URL("https://er-cdn.ertelecom.ru/content/public/r"+art+"/400x240:crop").openConnection();c.setConnectTimeout(5000);c.setReadTimeout(5000);
            android.graphics.Bitmap b;try(InputStream in=c.getInputStream()){b=android.graphics.BitmapFactory.decodeStream(in);}if(b!=null)runOnUiThread(()->{if(seq==generation&&!isFinishing())channelLogo.setImageBitmap(b);});
        }catch(Exception ignored){}finally{if(c!=null)c.disconnect();}},"channel-logo").start();
    }
    private TextView addButton(LinearLayout row, String text, Runnable action) {
        TextView button = label(16, 0xffffffff); button.setText(text); button.setGravity(Gravity.CENTER);
        button.setPadding(22, 13, 22, 13); button.setFocusable(true); button.setClickable(true);
        android.graphics.drawable.GradientDrawable normal = new android.graphics.drawable.GradientDrawable(); normal.setColor(0xff333743); normal.setCornerRadius(22);
        android.graphics.drawable.GradientDrawable focused = new android.graphics.drawable.GradientDrawable(); focused.setColor(0xffffffff); focused.setCornerRadius(22);
        button.setBackground(normal); button.setOnFocusChangeListener((v, has) -> { button.setBackground(has ? focused : normal); button.setTextColor(has ? 0xff161820 : 0xffffffff); ui.removeCallbacks(hide); });
        button.setOnClickListener(v -> { action.run(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = 14; row.addView(button, lp); return button;
    }
    private void togglePause() {
        if(video.ended()&&!archiveLoading){desiredPlay=true;video.seekTo(0);video.start();showInfo();return;}
        if(archiveLoading){desiredPlay=!desiredPlay;pendingPlay=desiredPlay;showInfo();return;}
        if(playbackError){if(archiveMode&&archiveProgram!=null)openArchive(archiveProgram,video.position(),true);else play(index);return;}
        desiredPlay=!video.wantsToPlay();
        if(!archiveMode&&!desiredPlay&&currentProgram!=null&&channels.optJSONObject(index).optBoolean("catchup")){openArchive(currentProgram,currentProgramPosition(),false);return;}
        if(desiredPlay){if(video.ended())video.seekTo(0);video.start();}else video.pause();
        savePlayback();showInfo();pauseButton.requestFocus();if(!desiredPlay)ui.removeCallbacks(hide);updatePlaybackUi();
    }
    private boolean isFavorite(long id) { return getSharedPreferences("movix_favorites", 0).getBoolean("ch_" + id, false); }
    private void toggleFavorite() {
        if (index < 0) return; long id = channels.optJSONObject(index).optLong("id");
        boolean value = !isFavorite(id);favoriteButton.setContentDescription(value?"Удалить из избранного":"В избранное"); getSharedPreferences("movix_favorites", 0).edit().putBoolean("ch_" + id, value).apply();
        favoriteButton.setAlpha(value ? 1f : 0.75f);
    }
    private void showChannels() {
        if(channelPicker!=null&&channelPicker.isShowing())return;
        ui.removeCallbacks(hide);overlay.setVisibility(View.GONE);topPanel.setVisibility(View.GONE);channelPicker=MovixChannelPicker.show(this,channels,index,this::play);
        channelPicker.setOnDismissListener(d->{channelPicker=null;showInfo();});
    }
    private TextView label(int size, int color) { TextView v = new TextView(this); v.setTextColor(color); v.setTextSize(size); v.setMaxLines(1); v.setEllipsize(android.text.TextUtils.TruncateAt.END); return v; }
    private void play(int i) {
        if (i<0 || i>=channels.length()) return;
        savePlayback();archiveMode=false;archiveLoading=false;archiveProgram=null;pendingPosition=-1;pendingPlay=true;desiredPlay=true;timeline.cancel();
        JSONObject ch = channels.optJSONObject(i); long id=ch.optLong("id"), hls=ch.optLong("hls");
        getSharedPreferences("movix_channels",0).edit().putLong("last_channel",id).apply();
        if(hls==0){notice("Оператор не предоставил поток для этого канала");return;}
        if(index!=i)previousChannel=index;index=i;liveClockCorrection=0;iconPlaying=null;playbackError=false;seeking=false;ui.removeCallbacks(applySeek);final int seq=++generation; setNativeIcon(pauseButton,R.drawable.native_pause); favoriteButton.setAlpha(isFavorite(id) ? 1f : 0.75f); video.stopPlayback(); loading.setText("Загружаем канал…"); loading.setVisibility(View.VISIBLE);
        topChannel.setText(ch.optInt("number")+"  "+ch.optString("name")); title.setText("ПРЯМОЙ ЭФИР"); now.setText(ch.optString("name")); next.setText("");
        timeline.model(0,0,0);elapsedLabel.setText("");endLabel.setText("");loadChannelLogo(ch,seq);
        currentProgram=null;schedule=new JSONArray();
        programShelf.load(ch,this::chooseProgram);showInfo();
        new Thread(() -> {
            try {
                String url=MovixApi.stream(getApplicationContext(), id, hls);
                runOnUiThread(() -> { if (!isFinishing() && seq==generation) { video.setVideoURI(Uri.parse(url),desiredPlay&&foreground); video.requestFocus(); } });
                try { JSONArray rows=MovixApi.scheduleRows(getApplicationContext(),ch.optLong("epg"));
                    runOnUiThread(()->{if(seq==generation){schedule=rows;refreshProgramme();updatePlaybackUi();}});
                }catch(Exception ignored){}

            } catch (Exception ex) { runOnUiThread(() -> { if (seq==generation) loading.setText("Не удалось загрузить канал"); }); }
        }, "movix-stream").start();
    }
    private boolean channelNumberKey(KeyEvent e){
        int key=e.getKeyCode();boolean down=e.getAction()==KeyEvent.ACTION_DOWN;
        if(key<KeyEvent.KEYCODE_0||key>KeyEvent.KEYCODE_9)return false;
        if(down&&e.getRepeatCount()==0){
            if(channelDigits.length()>=4)channelDigits.delete(0,channelDigits.length()-3);
            channelDigits.append((char)('0'+(key-KeyEvent.KEYCODE_0)));
            channelInputHandler.removeCallbacks(commitChannelDigits);channelInputHandler.postDelayed(commitChannelDigits,1200);
            showInfo();topChannel.setText("Канал "+channelDigits);controlCaption.setText("Ввод номера канала");playbackStatus.setText("Канал "+channelDigits+" · OK — открыть");
        }
        if(!down&&(key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_DPAD_CENTER))commitChannelDigits.run();
        return true;
    }
    private void commitChannelNumber(){
        if(channelDigits.length()==0)return;int wanted;try{wanted=Integer.parseInt(channelDigits.toString());}catch(Exception e){channelDigits.setLength(0);return;}
        int found=-1;for(int i=0;i<channels.length();i++)if(channels.optJSONObject(i).optInt("number")==wanted){found=i;break;}
        String typed=channelDigits.toString();channelDigits.setLength(0);channelInputHandler.removeCallbacks(commitChannelDigits);
        if(found>=0){play(found);showInfo();}else{notice("Канал "+typed+" не найден в списке Movix");topChannel.setText(index>=0?channels.optJSONObject(index).optInt("number")+"  "+channels.optJSONObject(index).optString("name"):"Канал");}
    }
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if(channelNumberKey(e))return true;
        int key=e.getKeyCode();boolean down=e.getAction()==KeyEvent.ACTION_DOWN;
        if(down){lastKeyCode=key;keyFocus=timeline.hasFocus()?"timeline":controlsRow.hasFocus()?"controls":programShelf.hasFocus()?"guide":"other";android.util.Log.i("MovixRemote","key="+key+" focus="+keyFocus);}
        if(remoteKey(e))return true;
        if(timeline.hasFocus()&&timeline.key(e))return true;
        if(swallowCenterUp&&!down&&(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER)){swallowCenterUp=false;return true;}
        if(key==KeyEvent.KEYCODE_BACK&&overlay.getVisibility()==View.VISIBLE){if(!down){if(guideExpanded)setGuideExpanded(false);else{overlay.setVisibility(View.GONE);topPanel.setVisibility(View.GONE);video.requestFocus();}}return true;}
        if((key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER)&&down&&(overlay.getVisibility()!=View.VISIBLE||(!overlay.hasFocus()&&!topPanel.hasFocus()))){swallowCenterUp=true;showInfo();pauseButton.requestFocus();ui.removeCallbacks(hide);return true;}
        if((key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT)&&!overlay.hasFocus()&&!topPanel.hasFocus()){
            if(down){showInfo();timeline.requestFocus();timeline.key(e);}return true;
        }
        if(key==KeyEvent.KEYCODE_DPAD_DOWN){if(down){showInfo();ui.removeCallbacks(hide);if(topPanel.hasFocus())pauseButton.requestFocus();else if(guideExpanded&&programShelf.focusCards()){}else if(controlsRow.hasFocus())timeline.requestFocus();else if(timeline.hasFocus()||navigationRow.hasFocus()||!guideExpanded)setGuideExpanded(true);}return true;}
        if(key==KeyEvent.KEYCODE_DPAD_UP){if(down){showInfo();ui.removeCallbacks(hide);if(programShelf.hasFocus()){if(!programShelf.focusDayControls())setGuideExpanded(false);}else if(timeline.hasFocus()||navigationRow.hasFocus())pauseButton.requestFocus();else topPanel.getChildAt(2).requestFocus();}return true;}
        return super.dispatchKeyEvent(e);
    }
    boolean remoteKey(KeyEvent event){
        if(!foreground)return false;
        int key=event.getKeyCode();
        if(channelPicker!=null&&channelPicker.isShowing()&&(key==KeyEvent.KEYCODE_PAGE_UP||key==KeyEvent.KEYCODE_PAGE_DOWN))return false;
        if(key==KeyEvent.KEYCODE_GUIDE||key==KeyEvent.KEYCODE_MENU||key==KeyEvent.KEYCODE_TV||key==KeyEvent.KEYCODE_INFO){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){if(key==KeyEvent.KEYCODE_GUIDE){showInfo();setGuideExpanded(true);}else if(key==KeyEvent.KEYCODE_INFO){showInfo();pauseButton.requestFocus();}else showChannels();}return true;}
        if(channels.length()==0)return false;
        boolean up=key==KeyEvent.KEYCODE_CHANNEL_UP||key==KeyEvent.KEYCODE_PAGE_UP||key==KeyEvent.KEYCODE_MEDIA_NEXT;
        boolean down=key==KeyEvent.KEYCODE_CHANNEL_DOWN||key==KeyEvent.KEYCODE_PAGE_DOWN||key==KeyEvent.KEYCODE_MEDIA_PREVIOUS;
        if(up||down||key==KeyEvent.KEYCODE_LAST_CHANNEL){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0&&android.os.SystemClock.uptimeMillis()-lastChannelPress>220){lastChannelPress=android.os.SystemClock.uptimeMillis();if(channelPicker!=null)channelPicker.dismiss();play(key==KeyEvent.KEYCODE_LAST_CHANNEL&&previousChannel>=0?previousChannel:(index+(up?1:-1)+channels.length())%channels.length());}return true;}
        return false;
    }
    private void setupMediaSession(){
        mediaSession=new android.media.session.MediaSession(this,"Movix TV");mediaSession.setFlags(android.media.session.MediaSession.FLAG_HANDLES_MEDIA_BUTTONS|android.media.session.MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new android.media.session.MediaSession.Callback(){
            @Override public void onPlay(){if(!desiredPlay||video.ended())togglePause();}
            @Override public void onPause(){if(desiredPlay)togglePause();}
            @Override public void onStop(){savePlayback();finish();}
            @Override public void onSeekTo(long position){commitSeek(position);}
            @Override public void onFastForward(){seekBy(10000);}
            @Override public void onRewind(){seekBy(-10000);}
            @Override public void onSkipToNext(){if(channels.length()>0)play((index+1)%channels.length());}
            @Override public void onSkipToPrevious(){if(channels.length()>0)play((index-1+channels.length())%channels.length());}
            @Override public boolean onMediaButtonEvent(Intent intent){KeyEvent e=intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);return e!=null&&remoteKey(e)||super.onMediaButtonEvent(intent);}
        });mediaSession.setActive(true);
    }
    @Override public boolean onKeyDown(int key,KeyEvent event){
        if(key==KeyEvent.KEYCODE_CHANNEL_DOWN){play(Math.max(0,index-1));return true;}
        if(key==KeyEvent.KEYCODE_CHANNEL_UP){play(Math.min(channels.length()-1,index+1));return true;}
        if(key==KeyEvent.KEYCODE_DPAD_CENTER&&channelDigits.length()>0){commitChannelNumber();return true;}
        if(key==KeyEvent.KEYCODE_MEDIA_PLAY){if(archiveLoading){desiredPlay=pendingPlay=true;}else if(!video.wantsToPlay())togglePause();return true;}
        if(key==KeyEvent.KEYCODE_MEDIA_PAUSE){if(desiredPlay)togglePause();return true;}
        if(key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE){togglePause();return true;}
        if(key==KeyEvent.KEYCODE_MEDIA_REWIND){seekBy(-10000);return true;}
        if(key==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD){seekBy(10000);return true;}
        if(key==KeyEvent.KEYCODE_MEDIA_STOP){finish();return true;}
        if(key==KeyEvent.KEYCODE_GUIDE){showInfo();setGuideExpanded(true);return true;}
        if(key==KeyEvent.KEYCODE_INFO){showInfo();pauseButton.requestFocus();return true;}
        return super.onKeyDown(key,event);
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);long id=intent.getLongExtra("channel",index<0?0:channels.optJSONObject(index).optLong("id"));
        for(int i=0;i<channels.length();i++)if(channels.optJSONObject(i).optLong("id")==id){play(i);break;}
        try{String supplied=intent.getStringExtra("program");if(supplied!=null)chooseProgram(new JSONObject(supplied));else if(intent.getBooleanExtra("resume",false))maybePromptSavedResume();}catch(Exception ignored){}
    }
    @Override protected void onSaveInstanceState(Bundle out){
        savePlayback();super.onSaveInstanceState(out);if(index<0)return;out.putLong("channel",channels.optJSONObject(index).optLong("id"));JSONObject p=archiveMode?archiveProgram:currentProgram;
        if(p!=null){out.putString("program",p.toString());out.putLong("position",transportPosition());out.putBoolean("playing",desiredPlay);}
    }
    @Override protected void onPause() {
        foreground=false;if(active==this)active=null;if(mediaSession!=null)mediaSession.setActive(false);savePlayback();resumeAfterBackground=desiredPlay;ui.removeCallbacks(playbackTick);
        if(resumeAfterBackground)video.pause();
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();foreground=true;active=this;if(mediaSession!=null)mediaSession.setActive(true);
        if(resumeAfterBackground&&video!=null&&!archiveLoading&&!playbackError){video.start();resumeAfterBackground=false;}
        ui.removeCallbacks(playbackTick);ui.post(playbackTick);
    }
    @Override public void dump(String prefix,java.io.FileDescriptor fd,java.io.PrintWriter writer,String[] args){
        super.dump(prefix,fd,writer,args);try{JSONObject state=new JSONObject();state.put("channel",index<0?0:channels.optJSONObject(index).optLong("id"));state.put("archive",archiveMode);state.put("loadingArchive",archiveLoading);state.put("ready",video.ready());state.put("playing",video.wantsToPlay());state.put("position",video.position());state.put("duration",video.duration());state.put("liveOffset",video.liveOffset());state.put("error",playbackError);state.put("pending",pendingPosition);state.put("scrubbing",timeline.editing());state.put("seekTarget",timeline.target());state.put("programme",archiveMode&&archiveProgram!=null?archiveProgram.optLong("id"):currentProgram==null?0:currentProgram.optLong("id"));state.put("clockCorrection",liveClockCorrection);state.put("logicalPosition",transportPosition());state.put("timelineStart",(archiveMode?archiveProgram:currentProgram)==null?0:(archiveMode?archiveProgram:currentProgram).optLong("start"));state.put("timelineDuration",displayLength());state.put("available",transportLength());state.put("programmeTitle",now.getText());state.put("nextProgramme",next.getText());state.put("lastKey",lastKeyCode);state.put("keyFocus",keyFocus);state.put("format",video.format());state.put("focus",timeline.hasFocus()?"timeline":programShelf.hasFocus()?"guide":controlsRow.hasFocus()?"controls":topPanel.hasFocus()?"channels":"video");writer.println("MOVIX_STATE "+state);}catch(Exception ignored){}
    }
    @Override protected void onDestroy() { channelInputHandler.removeCallbacksAndMessages(null); generation++;if(active==this)active=null;if(resumeDialog!=null&&resumeDialog.isShowing())resumeDialog.dismiss();if(mediaSession!=null)mediaSession.release();ui.removeCallbacksAndMessages(null); video.release(); super.onDestroy(); }
}
