package com.techgenius.tvosmovix;

import android.app.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.*;

/** Top channel drawer, navigable entirely by the TV remote. */
final class MovixChannelPicker {
    interface Selected { void channel(int index); }
    private static final android.util.LruCache<Long,Bitmap> art=new android.util.LruCache<>(40);
    private static final ExecutorService loader=Executors.newFixedThreadPool(2);
    static GradientDrawable background(boolean focused){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,focused?new int[]{0xffeaf0fa,0xffc7d4e8}:new int[]{0xf5404c60,0xf5222c3d});d.setCornerRadius(20);d.setStroke(focused?3:1,focused?Color.WHITE:0x50ffffff);return d;}
    static TextView text(Activity a,String value,int size){TextView t=new TextView(a);t.setText(value);t.setTextSize(size);t.setTextColor(Color.WHITE);t.setSingleLine(true);t.setEllipsize(android.text.TextUtils.TruncateAt.END);return t;}
    private static String channelName(JSONObject channel){String name=channel.optString("name");int dash=name.indexOf(" - ");return dash>0?name.substring(0,dash):name;}
    private static final class Row {
        LinearLayout root;ImageView logo;TextView number,name,programme;long channel;
    }
    static Dialog show(Activity a,JSONArray channels,int selected,Selected action){
        Dialog dialog=new Dialog(a);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=new LinearLayout(a);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(28,22,28,22);panel.setBackground(background(false));
        LinearLayout header=new LinearLayout(a);header.setGravity(Gravity.CENTER_VERTICAL);TextView count=text(a,"",12);
        header.addView(text(a,"Каналы",23),new LinearLayout.LayoutParams(0,62,1));header.addView(count);panel.addView(header);
        LinearLayout tabs=new LinearLayout(a);tabs.setPadding(0,8,0,12);panel.addView(tabs);
        EditText search=new EditText(a);search.setTextColor(Color.WHITE);search.setHintTextColor(0xffb4c0d3);search.setTextSize(14);search.setSingleLine(true);search.setHint("Поиск по названию или номеру");search.setPadding(16,0,16,0);search.setBackground(background(false));search.setShowSoftInputOnFocus(false);
        LinearLayout.LayoutParams searchLp=new LinearLayout.LayoutParams(-1,62);searchLp.bottomMargin=12;panel.addView(search,searchLp);
        ListView list=new ListView(a);list.setDivider(new ColorDrawable(Color.TRANSPARENT));list.setDividerHeight(8);list.setSelector(new ColorDrawable(Color.TRANSPARENT));list.setVerticalScrollBarEnabled(false);list.setPadding(3,3,3,3);panel.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        TextView following=text(a,"",13);following.setSingleLine(false);following.setMaxLines(2);following.setPadding(8,14,8,0);panel.addView(following,new LinearLayout.LayoutParams(-1,96));
        TextView help=text(a,"OK — включить   ·   Назад — закрыть",11);help.setTextColor(0xffb9c6d9);help.setPadding(8,8,8,0);panel.addView(help,new LinearLayout.LayoutParams(-1,42));
        ArrayList<Integer> visible=new ArrayList<>();ArrayList<TextView> filters=new ArrayList<>();int[] filter={0},captionVersion={0};
        java.util.function.BiConsumer<Row,Boolean> paintRow=(row,focus)->{
            row.root.setBackground(background(focus));row.name.setTextColor(focus?0xff172131:Color.WHITE);row.number.setTextColor(focus?0xff45536a:0xffb8c7db);row.programme.setTextColor(focus?0xff40516a:0xffb8c7db);
        };
        BaseAdapter adapter=new BaseAdapter(){
            public int getCount(){return visible.size();}public Object getItem(int p){return channels.optJSONObject(visible.get(p));}public long getItemId(int p){return channels.optJSONObject(visible.get(p)).optLong("id");}public boolean hasStableIds(){return true;}
            public View getView(int p,View recycled,ViewGroup parent){
                Row row;
                if(recycled!=null&&recycled.getTag() instanceof Row)row=(Row)recycled.getTag();else{
                    row=new Row();row.root=new LinearLayout(a);row.root.setOrientation(LinearLayout.HORIZONTAL);row.root.setGravity(Gravity.CENTER_VERTICAL);row.root.setPadding(14,10,14,10);row.root.setLayoutParams(new AbsListView.LayoutParams(-1,108));
                    row.number=text(a,"",12);row.number.setGravity(Gravity.CENTER);row.root.addView(row.number,new LinearLayout.LayoutParams(42,-1));
                    row.logo=new ImageView(a);row.logo.setScaleType(ImageView.ScaleType.FIT_CENTER);LinearLayout.LayoutParams artLp=new LinearLayout.LayoutParams(86,62);artLp.rightMargin=16;row.root.addView(row.logo,artLp);
                    LinearLayout copy=new LinearLayout(a);copy.setOrientation(LinearLayout.VERTICAL);copy.setGravity(Gravity.CENTER_VERTICAL);row.name=text(a,"",16);row.name.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));row.programme=text(a,"",12);copy.addView(row.name,new LinearLayout.LayoutParams(-1,42));copy.addView(row.programme,new LinearLayout.LayoutParams(-1,34));row.root.addView(copy,new LinearLayout.LayoutParams(0,-1,1));row.root.setTag(row);
                }
                JSONObject channel=channels.optJSONObject(visible.get(p));long id=channel.optLong("id");row.channel=id;row.logo.setImageDrawable(null);row.number.setText(String.valueOf(channel.optInt("number")));row.name.setText(channelName(channel)+(visible.get(p)==selected?"  •":""));row.programme.setText("Прямой эфир");paintRow.accept(row,list.hasFocus()&&p==list.getSelectedItemPosition());
                final Row target=row;long poster=channel.optLong("poster");Bitmap cached=art.get(poster);if(cached!=null)row.logo.setImageBitmap(cached);
                loader.execute(()->{if(!dialog.isShowing()||target.channel!=id)return;
                    if(cached==null&&poster>0){HttpURLConnection c=null;try{c=(HttpURLConnection)new URL("https://er-cdn.ertelecom.ru/content/public/r"+poster+"/240x140:crop").openConnection();c.setConnectTimeout(3500);c.setReadTimeout(3500);Bitmap image;try(InputStream in=c.getInputStream()){image=BitmapFactory.decodeStream(in);}if(image!=null){art.put(poster,image);target.logo.post(()->{if(dialog.isShowing()&&target.channel==id)target.logo.setImageBitmap(image);});}}catch(Exception ignored){}finally{if(c!=null)c.disconnect();}}
                    try{String[] epg=MovixApi.programs(a.getApplicationContext(),channel.optLong("epg"));target.programme.post(()->{if(dialog.isShowing()&&target.channel==id)target.programme.setText(epg[0].replace("Сейчас · ",""));});}catch(Exception ignored){}
                });return row.root;
            }
        };list.setAdapter(adapter);
        Runnable paint=()->{for(int i=0;i<list.getChildCount();i++){Row row=(Row)list.getChildAt(i).getTag();paintRow.accept(row,list.hasFocus()&&list.getFirstVisiblePosition()+i==list.getSelectedItemPosition());}};
        Runnable populate=()->{String query=search.getText().toString().trim().toLowerCase(new Locale("ru"));visible.clear();for(int i=0;i<channels.length();i++){JSONObject c=channels.optJSONObject(i);if(filter[0]==1&&!a.getSharedPreferences("movix_favorites",0).getBoolean("ch_"+c.optLong("id"),false))continue;if(filter[0]==2&&!c.optBoolean("catchup"))continue;if(!query.isEmpty()&&!c.optString("name").toLowerCase(new Locale("ru")).contains(query)&&!String.valueOf(c.optInt("number")).equals(query))continue;visible.add(i);}adapter.notifyDataSetChanged();count.setText(visible.size()+" / "+channels.length());following.setText(visible.isEmpty()?"Каналы не найдены":"");list.setSelection(Math.max(0,visible.indexOf(selected)));list.post(paint);};
        String[] titles={"Все","Избранное","Архив"};
        Runnable paintFilters=()->{for(int i=0;i<filters.size();i++){TextView t=filters.get(i);boolean focus=t.hasFocus();t.setBackground(background(focus));t.setTextColor(focus?0xff172131:i==filter[0]?Color.WHITE:0xffa4b3c9);t.setAlpha(i==filter[0]||focus?1f:.75f);}};
        for(int i=0;i<titles.length;i++){int which=i;TextView t=text(a,titles[i],13);t.setGravity(Gravity.CENTER);t.setPadding(12,0,12,0);t.setFocusable(true);t.setClickable(true);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,52,1);if(i>0)lp.leftMargin=8;tabs.addView(t,lp);filters.add(t);t.setOnFocusChangeListener((v,f)->paintFilters.run());t.setOnClickListener(v->{filter[0]=which;paintFilters.run();populate.run();if(!visible.isEmpty())list.requestFocus();});}
        search.setOnFocusChangeListener((v,focus)->{search.setBackground(background(focus));search.setTextColor(focus?0xff172131:Color.WHITE);search.setHintTextColor(focus?0xff55657b:0xffb4c0d3);});
        search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){populate.run();}public void afterTextChanged(android.text.Editable e){}});
        search.setOnKeyListener((v,key,event)->{if((key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER)&&event.getAction()==KeyEvent.ACTION_UP){((android.view.inputmethod.InputMethodManager)a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).showSoftInput(search,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);return true;}return false;});
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int position,long id){paint.run();if(position<0||position>=visible.size())return;JSONObject c=channels.optJSONObject(visible.get(position));int seq=++captionVersion[0];following.setText(channelName(c));list.postDelayed(()->{if(seq!=captionVersion[0])return;loader.execute(()->{try{String[] epg=MovixApi.programs(a.getApplicationContext(),c.optLong("epg"));following.post(()->{if(dialog.isShowing()&&seq==captionVersion[0])following.setText(epg[1].isEmpty()?"Программа временно недоступна":epg[1]);});}catch(Exception ignored){}});},180);}});
        list.setOnKeyListener((v,key,event)->{if(key==KeyEvent.KEYCODE_DPAD_LEFT){if(event.getAction()==KeyEvent.ACTION_DOWN)filters.get(filter[0]).requestFocus();return true;}return false;});
        list.setOnFocusChangeListener((v,f)->list.post(paint));list.setOnItemClickListener((p,v,position,id)->{int chosen=visible.get(position);dialog.dismiss();action.channel(chosen);});
        dialog.setContentView(panel);Window w=dialog.getWindow();if(w!=null){w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.setDimAmount(.22f);w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);}
        dialog.show();if(w!=null){int height=a.getResources().getDisplayMetrics().heightPixels;w.setLayout(780,height-72);WindowManager.LayoutParams lp=w.getAttributes();lp.x=36;w.setAttributes(lp);w.getDecorView().setSystemUiVisibility(5894);}
        paintFilters.run();populate.run();list.requestFocus();return dialog;
    }
    static void options(Activity a,String title,String[] options,java.util.function.IntConsumer action){
        Dialog dialog=new Dialog(a);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);LinearLayout box=new LinearLayout(a);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(30,24,30,24);box.setBackground(background(false));TextView heading=text(a,title,20);box.addView(heading,new LinearLayout.LayoutParams(-1,70));
        for(int i=0;i<options.length;i++){int which=i;TextView row=text(a,options[i],15);row.setFocusable(true);row.setClickable(true);row.setPadding(20,12,20,12);row.setBackground(background(false));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,64);lp.bottomMargin=8;box.addView(row,lp);row.setOnFocusChangeListener((v,f)->{row.setBackground(background(f));row.setTextColor(f?0xff172131:Color.WHITE);});row.setOnClickListener(v->{dialog.dismiss();action.accept(which);});}
        dialog.setContentView(box);Window w=dialog.getWindow();if(w!=null)w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));dialog.show();if(w!=null){w.setLayout(920,-2);w.getDecorView().setSystemUiVisibility(5894);}
    }
}
