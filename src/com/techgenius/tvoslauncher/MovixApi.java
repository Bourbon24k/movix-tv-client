package com.techgenius.tvosmovix;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class MovixApi {
    static final class Failure extends Exception { Failure(String message){super(message);} }
    private static final String BASE = "https://discovery-stb3.ertelecom.ru";
    private static String token, device;
    private static long tokenAt;
    static synchronized boolean signedIn(Context c) { importSession(c); return c.getSharedPreferences("movix_session",0).getBoolean("bound",false); }
    private static void importSession(Context c) {
        java.io.File dir=c.getExternalFilesDir(null); if(dir==null)return;
        java.io.File file=new java.io.File(dir,"movix_session.json"); if(!file.isFile())return;
        try {
            byte[] bytes=java.nio.file.Files.readAllBytes(file.toPath());file.delete();
            JSONObject data=new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
            if(!data.optBoolean("is_bound")||data.optString("token").isEmpty())return;
            c.getSharedPreferences("movix_session",0).edit().putBoolean("bound",true).putString("token",data.getString("token"))
                .putString("device",data.getString("device")).putLong("expires",data.getLong("expires")).commit();
            token=null;device=null;
        }catch(Exception ignored){file.delete();}
    }
    private static final ConcurrentHashMap<Long, String[]> guide = new ConcurrentHashMap<>();
    private static long guideAt;
    private static JSONObject request(String path, String auth) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(path.startsWith("https://") ? path : BASE + path).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(8000);
        c.setRequestProperty("X-Device-Info", device); c.setRequestProperty("View", "stb3");
        c.setRequestProperty("X-App-Version", "3.12.0"); c.setRequestProperty("User-Agent", "Mozilla/5.0");
        if (auth != null) c.setRequestProperty("X-Auth-Token", auth);
        int status=c.getResponseCode();
        try (InputStream in = status>=400?c.getErrorStream():c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if(in==null)throw new java.io.IOException("HTTP "+status);
            byte[] b = new byte[4096]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n);
            JSONObject response=new JSONObject(out.toString("UTF-8"));
            if(status==401)throw new Failure("Сессия Дом.ру истекла. Необходим повторный вход");
            if(status==403||response.has("result")&&response.optInt("result",1)==0)throw new Failure("Оператор не разрешает просмотр этой записи или канала");
            if(status>=400)throw new java.io.IOException("HTTP "+status);
            return response;
        } finally { c.disconnect(); }
    }
    private static synchronized String token(Context c) throws Exception {
        importSession(c);
        android.content.SharedPreferences session=c.getSharedPreferences("movix_session",0);
        if(session.getBoolean("bound",false)) {
            device=session.getString("device","");token=session.getString("token","");
            if(session.getLong("expires",0)<System.currentTimeMillis()/1000+86400) {
                JSONObject refreshed=request("/token/refresh",token);
                token=refreshed.getString("token");
                session.edit().putString("token",token).putLong("expires",refreshed.getLong("expires")).commit();
            }
            return token;
        }
        if(device==null) {
            device=c.getSharedPreferences("movix_player",0).getString("device","");
            if(device.isEmpty()){device=UUID.randomUUID().toString().replace("-","").substring(0,16);c.getSharedPreferences("movix_player",0).edit().putString("device",device).apply();}
        }
        long now=System.currentTimeMillis();
        if(token==null||now-tokenAt>30*60*1000L){token=request("/token/device?client_id=er_android_device&device_id="+device+"&timestamp="+now/1000,null).getString("token");tokenAt=now;}
        return token;
    }
    static String archive(Context c,JSONObject channel,JSONObject program) throws Exception {
        if(!signedIn(c))throw new IllegalStateException("Для архива требуется вход в Дом.ру");
        JSONObject response=request("/catchup/stream?epg_schedule_item_id="+program.getLong("id")+"&channel_id="+channel.getLong("id")+"&start="+program.getLong("start")+"&duration="+program.getLong("duration"),token(c));
        String url=response.optString("url");
        if(url.isEmpty())throw new Failure("Запись этой передачи недоступна у оператора");
        return url;
    }
    private static final ConcurrentHashMap<Long,JSONObject> programPages=new ConcurrentHashMap<>();
    static JSONObject programDetails(Context c,long id) throws Exception {
        JSONObject cached=programPages.get(id);if(cached!=null)return cached;
        JSONObject response=request("https://discovery-stb3.ertelecom.ru/api/v3/pages/schedule/"+id,token(c));
        JSONObject data=response.optJSONObject("data");
        JSONArray shelves=data==null?null:data.optJSONArray("showcases");
        if(shelves!=null)for(int i=0;i<shelves.length();i++){
            JSONObject shelf=shelves.optJSONObject(i);if(shelf==null)continue;
            JSONArray items=shelf.optJSONArray("items");if(items==null)continue;
            for(int j=0;j<items.length();j++){JSONObject item=items.optJSONObject(j);if(item.optLong("id")==id){if(programPages.size()>120)programPages.clear();programPages.put(id,item);return item;}}
        }
        return new JSONObject();
    }
    static String stream(Context c, long id, long hls) throws Exception {
        return request("/resource/get_url/" + id + "/" + hls, token(c)).getString("url");
    }
    static JSONArray scheduleRows(Context c, long epg) throws Exception {
        if (epg == 0) return new JSONArray();
        long now=System.currentTimeMillis()/1000;
        return scheduleRows(c,epg,now-21600,now+43200);
    }
    static JSONArray scheduleRows(Context c,long epg,long from,long to) throws Exception {
        String path="/collection/epg.schedule/query/select/id,program,start,duration,title,channel_id/dimension/start_dt/span/"+from+","+to+"/dimension/channel_id/in/"+epg+"/enum/0,300";
        JSONArray rows=request(path,token(c)).optJSONArray("collection");return rows==null?new JSONArray():rows;
    }
    static String[] schedule(Context c, long epg) throws Exception {
        if (epg == 0) return new String[0];
        long now = System.currentTimeMillis()/1000;
        String path = "/collection/epg.schedule/query/select/id,program,start,duration,title,channel_id/dimension/start_dt/span/" + (now-7200) + "," + (now+21600) + "/dimension/channel_id/in/" + epg + "/enum/0,30";
        JSONArray rows = request(path, token(c)).optJSONArray("collection");
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        if (rows != null) for (int i=0; i<rows.length() && result.size()<8; i++) {
            JSONObject p=rows.optJSONObject(i); long start=p.optLong("start"); if (start+p.optLong("duration")<=now) continue;
            result.add(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(start*1000)) + "  " + p.optString("title"));
        }
        return result.toArray(new String[0]);
    }
    static String[] programs(Context c, long epg) throws Exception {
        if (epg == 0) return new String[]{"", ""};
        long now = System.currentTimeMillis()/1000;
        if (System.currentTimeMillis() - guideAt > 5*60*1000L) { guide.clear(); guideAt = System.currentTimeMillis(); }
        String[] cached = guide.get(epg); if (cached != null) return cached;
        String path = "/collection/epg.schedule/query/select/id,program,start,duration,title,channel_id/dimension/start_dt/span/" + (now-7200) + "," + (now+21600) + "/dimension/channel_id/in/" + epg + "/enum/0,30";
        JSONArray rows = request(path, token(c)).optJSONArray("collection");
        String current = "", next = ""; long nextTime = Long.MAX_VALUE;
        if (rows != null) for (int i=0; i<rows.length(); i++) {
            JSONObject p = rows.optJSONObject(i); long start = p.optLong("start"), end = start + p.optLong("duration");
            String title = p.optString("title", "");
            if (start <= now && end > now) current = "Сейчас · " + title;
            if (start > now && start < nextTime) { nextTime=start; next="Далее " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(start*1000)) + " · " + title; }
        }
        String[] result = new String[]{current,next}; guide.put(epg,result); return result;
    }
}
