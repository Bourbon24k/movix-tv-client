package com.techgenius.tvosmovix;

import android.content.Context;
import android.net.Uri;
import android.view.SurfaceView;
import android.view.Gravity;
import android.widget.FrameLayout;
import androidx.media3.common.*;
import androidx.media3.exoplayer.*;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import java.util.*;

/** HLS decoder, track selection and transport state. No operator API logic belongs here. */
@UnstableApi
final class MovixPlayback extends FrameLayout {
    interface Ready { void prepared(); }
    interface Error { void failed(String message); }
    interface Changed { void changed(); }
    final ExoPlayer player;
    final DefaultTrackSelector selector;
    private final SurfaceView surface;
    Ready onReady;Error onError;Changed onChanged;
    private boolean readySent;
    private final Timeline.Window window=new Timeline.Window();
    private int videoW,videoH;private float pixelRatio=1;
    private int qualityHeight;
    MovixPlayback(Context context){
        super(context);setFocusable(true);setFocusableInTouchMode(false);
        surface=new SurfaceView(context);surface.setFocusable(false);addView(surface,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        selector=new DefaultTrackSelector(context);
        player=new ExoPlayer.Builder(context).setTrackSelector(selector).setLoadControl(new DefaultLoadControl.Builder().setBufferDurationsMs(15000,45000,1500,3000).build()).build();
        setQuality(context.getSharedPreferences("movix_player",0).getInt("quality_height",0));
        player.setVideoSurfaceView(surface);
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true);
        player.setHandleAudioBecomingNoisy(true);
        player.addListener(new Player.Listener(){
            @Override public void onPlaybackStateChanged(int state){
                if(state==Player.STATE_READY&&!readySent){readySent=true;android.util.Log.i("MovixPlayback","READY live="+live()+" duration="+duration());if(onReady!=null)onReady.prepared();}
                notifyChanged();
            }
            @Override public void onEvents(Player p,Player.Events e){notifyChanged();}
            @Override public void onPlayerError(PlaybackException error){if(onError!=null)onError.failed(error.getErrorCodeName());}
            @Override public void onVideoSizeChanged(VideoSize size){videoW=size.width;videoH=size.height;pixelRatio=size.pixelWidthHeightRatio;resize();android.util.Log.i("MovixPlayback","VIDEO "+videoW+"x"+videoH);}
        });
    }
    private void notifyChanged(){if(onChanged!=null)onChanged.changed();}
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){super.onSizeChanged(w,h,ow,oh);resize();}
    private void resize(){
        if(videoW<=0||videoH<=0)return;post(()->{int availableW=getWidth(),availableH=getHeight();if(availableW<=0||availableH<=0)return;float aspect=(float)videoW*pixelRatio/videoH;int targetW=availableW,targetH=(int)(targetW/aspect);if(targetH>availableH){targetH=availableH;targetW=(int)(targetH*aspect);}surface.setLayoutParams(new FrameLayout.LayoutParams(targetW,targetH,Gravity.CENTER));});
    }
    void setQuality(int height){
        qualityHeight=height;
        DefaultTrackSelector.Parameters.Builder b=selector.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_VIDEO).setMaxVideoSize(Integer.MAX_VALUE,Integer.MAX_VALUE).setForceHighestSupportedBitrate(height==-1);
        if(height>0)b.setMaxVideoSize(Integer.MAX_VALUE,height);
        selector.setParameters(b);
    }
    int quality(){return qualityHeight;}
    int[] videoHeights(){TreeSet<Integer> heights=new TreeSet<>(Collections.reverseOrder());for(Tracks.Group g:player.getCurrentTracks().getGroups())if(g.getType()==C.TRACK_TYPE_VIDEO)for(int i=0;i<g.length;i++)if(g.isTrackSupported(i)&&g.getTrackFormat(i).height>0)heights.add(g.getTrackFormat(i).height);return heights.stream().mapToInt(Integer::intValue).toArray();}
    String format(){Format f=player.getVideoFormat();if(f==null)return "Определяем качество…";return f.width+" × "+f.height+(f.bitrate>0?" · "+String.format(Locale.getDefault(),"%.1f Мбит/с",f.bitrate/1000000f):"");}
    String[] trackLabels(int type){ArrayList<String> out=new ArrayList<>();for(Tracks.Group g:player.getCurrentTracks().getGroups())if(g.getType()==type)for(int i=0;i<g.length;i++)if(g.isTrackSupported(i)){Format f=g.getTrackFormat(i);out.add((f.label!=null?f.label:f.language!=null?new Locale(f.language).getDisplayLanguage(new Locale("ru")):"Дорожка "+(out.size()+1))+(f.channelCount>0?" · "+f.channelCount+" кан.":""));}return out.toArray(new String[0]);}
    void selectTrack(int type,int position){
        TrackSelectionParameters.Builder b=player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type,position==-2);
        if(position>=0){int current=0;outer:for(Tracks.Group g:player.getCurrentTracks().getGroups())if(g.getType()==type)for(int i=0;i<g.length;i++)if(g.isTrackSupported(i)){if(current++==position){b.setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(),i));break outer;}}}
        player.setTrackSelectionParameters(b.build());
    }
    void setVideoURI(Uri uri,boolean play){readySent=false;player.setMediaItem(MediaItem.fromUri(uri));player.setPlayWhenReady(play);player.prepare();}
    void setVideoURI(Uri uri){setVideoURI(uri,true);}
    void start(){player.play();android.util.Log.i("MovixPlayback","PLAY position="+position());}
    void pause(){player.pause();android.util.Log.i("MovixPlayback","PAUSE position="+position());}
    boolean isPlaying(){return player.isPlaying();}
    boolean wantsToPlay(){return player.getPlayWhenReady();}
    boolean ready(){return player.getPlaybackState()==Player.STATE_READY;}
    boolean ended(){return player.getPlaybackState()==Player.STATE_ENDED;}
    boolean loading(){return player.getPlaybackState()==Player.STATE_BUFFERING;}
    boolean live(){return player.isCurrentMediaItemLive();}
    boolean seekable(){return player.isCurrentMediaItemSeekable()&&duration()>0;}
    boolean canSeekBackward(){return seekable()&&position()>1200;}
    boolean canSeekForward(){return seekable()&&duration()-position()>1500;}
    long position(){return Math.max(0,player.getCurrentPosition());}
    long duration(){long d=player.getDuration();return d==C.TIME_UNSET?0:Math.max(0,d);}
    long liveOffset(){long d=player.getCurrentLiveOffset();return d==C.TIME_UNSET?0:Math.max(0,d);}
    long windowStart(){Timeline t=player.getCurrentTimeline();if(t.isEmpty())return 0;t.getWindow(player.getCurrentMediaItemIndex(),window);return window.windowStartTimeMs==C.TIME_UNSET?0:window.windowStartTimeMs;}
    void seekTo(long target){android.util.Log.i("MovixPlayback","SEEK target="+target);player.seekTo(Math.max(0,Math.min(Math.max(0,duration()-800),target)));}
    void goLive(){player.seekToDefaultPosition();player.play();}
    void recoverLive(){boolean play=wantsToPlay();readySent=false;player.seekToDefaultPosition();player.prepare();player.setPlayWhenReady(play);}
    void stopPlayback(){player.stop();player.clearMediaItems();readySent=false;}
    void release(){player.release();}
}
