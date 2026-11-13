package com.techgenius.tvosmovix;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.Locale;

/** Remote scrubber: arrows preview a position, OK commits, BACK cancels. */
final class MovixTimeline extends View {
    interface Commit { void seek(long position); }
    private final Paint paint=new Paint(3);
    private long position,duration,preview,clockStart,available;
    private boolean editing;
    Commit commit;
    MovixTimeline(Context c){super(c);setFocusable(true);setClickable(true);setContentDescription("Шкала времени. Влево и вправо — выбрать позицию, OK — перемотать");}
    void model(long p,long d,long start){model(p,d,start,d);}
    void model(long p,long d,long start,long limit){position=Math.max(0,p);duration=Math.max(0,d);available=Math.max(0,Math.min(duration,limit));clockStart=start;if(!editing)preview=position;else preview=Math.min(preview,available);invalidate();}
    boolean editing(){return editing;}
    long target(){return preview;}
    void cancel(){editing=false;preview=position;invalidate();}
    boolean key(KeyEvent event){
        int key=event.getKeyCode();boolean down=event.getAction()==KeyEvent.ACTION_DOWN;
        if(key==KeyEvent.KEYCODE_BACK&&editing){if(!down)cancel();return true;}
        if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){
            if(down&&duration>0){if(!editing){preview=position;editing=true;}int repeat=event.getRepeatCount();long step=repeat>25?300000:repeat>12?60000:repeat>4?30000:10000;
                preview=Math.max(0,Math.min(available-800,preview+(key==KeyEvent.KEYCODE_DPAD_RIGHT?step:-step)));invalidate();}
            return true;
        }
        if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){if(down){if(editing&&commit!=null){long target=preview;editing=false;commit.seek(target);}else performClick();invalidate();}return true;}
        return false;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    @Override protected void onFocusChanged(boolean gain,int direction,Rect old){super.onFocusChanged(gain,direction,old);if(!gain)cancel();invalidate();}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);float w=getWidth(),h=getHeight(),left=12,right=w-12,y=h-14;
        paint.setColor(0x40414b5c);c.drawRoundRect(left,y-3,right,y+3,3,3,paint);
        float availableX=left+(right-left)*(duration>0?Math.min(1,(float)available/duration):0);
        paint.setColor(hasFocus()?0x999daac1:0x7779869b);c.drawRoundRect(left,y-3,availableX,y+3,3,3,paint);
        float fraction=duration>0?(float)(editing?preview:position)/duration:0;float x=left+(right-left)*Math.max(0,Math.min(1,fraction));
        paint.setColor(0xffe6efff);c.drawRoundRect(left,y-3,x,y+3,3,3,paint);c.drawCircle(x,y,hasFocus()?9:5,paint);
        if(hasFocus()){
            paint.setTextSize(21);paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
            long p=editing?preview:position;String caption;
            if(clockStart>0)caption=new java.text.SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new java.util.Date(clockStart+p));
            else{long s=p/1000;caption=s>=3600?String.format(Locale.getDefault(),"%d:%02d:%02d",s/3600,(s/60)%60,s%60):String.format(Locale.getDefault(),"%02d:%02d",s/60,s%60);}
            float width=paint.measureText(caption)+28,bx=Math.max(width/2,Math.min(w-width/2,x));
            paint.setColor(0xe9354054);c.drawRoundRect(bx-width/2,0,bx+width/2,31,10,10,paint);paint.setColor(Color.WHITE);paint.setTextAlign(Paint.Align.CENTER);c.drawText(caption,bx,23,paint);paint.setTextAlign(Paint.Align.LEFT);
        }
    }
}
