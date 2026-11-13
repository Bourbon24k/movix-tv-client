package com.techgenius.tvosmovix;

import android.content.*;
import android.net.*;
import android.os.Bundle;
import android.widget.Toast;

/** v2rayNG owns the subscription and tunnel; the launcher sends its documented Tasker toggle. */
final class VpnControl {
    static final String PACKAGE="com.v2ray.ang";
    static boolean installed(Context c){try{c.getPackageManager().getPackageInfo(PACKAGE,0);return true;}catch(Exception e){return false;}}
    static boolean connected(Context c){
        try{ConnectivityManager cm=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);if(cm!=null)for(Network n:cm.getAllNetworks()){NetworkCapabilities caps=cm.getNetworkCapabilities(n);android.net.NetworkInfo info=cm.getNetworkInfo(n);if(caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&info!=null&&info.isConnected())return true;}}catch(Exception ignored){}
        return false;
    }
    static String status(Context c){return !installed(c)?"Не установлен":connected(c)?"Подключён":"Выключен";}
    static void toggle(Context c){
        if(!installed(c)){Toast.makeText(c,"Установите VPN-клиент",Toast.LENGTH_SHORT).show();return;}
        boolean start=!connected(c);Bundle settings=new Bundle();settings.putBoolean("tasker_extra_bundle_switch",start);settings.putString("tasker_extra_bundle_guid","Default");
        Intent intent=new Intent("com.twofortyfouram.locale.intent.action.FIRE_SETTING");intent.setComponent(new ComponentName(PACKAGE,PACKAGE+".receiver.TaskerReceiver"));intent.putExtra("com.twofortyfouram.locale.intent.extra.BUNDLE",settings);intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        try{c.sendBroadcast(intent);Toast.makeText(c,start?"Подключаем VPN…":"Отключаем VPN…",Toast.LENGTH_SHORT).show();}catch(Exception e){open(c);}
    }
    static void open(Context c){try{Intent i=c.getPackageManager().getLaunchIntentForPackage(PACKAGE);if(i!=null)c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}catch(Exception ignored){}}
}
