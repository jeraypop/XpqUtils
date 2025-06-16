package com.lygttpod.android.auto;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;

import android.widget.Toast;


import com.lygttpod.android.auto.wx.service.WXAccessibility;

import java.io.FileNotFoundException;
import java.net.URL;
import java.net.URLConnection;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import androidx.annotation.RequiresApi;
import xpq.friend.BuildConfig;
import xpq.friend.R;


import static android.content.Context.MODE_PRIVATE;
import static android.content.Context.VIBRATOR_SERVICE;



/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2021/1/4 0004  17:05
 * Description:This is Utilshezhi
 */
public  class Utilshezhi {
    //=========常量
    public static final String App_ID = BuildConfig.APPLICATION_ID;
    public static final String App_FP = ".FileProvider";

    public static final String moRenPkg = "长按每个软件导出全部法则";
    public static final String moRenAppName = "点击每个软件进入法则管理";

    public static final String LING_DONG_DAO = "lingdongdao";
    public static final String LING_DONG_DAO_DISMISS = "LING_DONG_DAO_DISMISS";
    public static final String SHUANGJISHOW = "SHUANGJISHOW";
    public static final String AUTOTIMEOUT = "AUTOTIMEOUT";

    public static final int LING_DONG_MSG = 555;
    public static final int COUNTDOWNTIMER_END = 5555;
    //========



    public static void getVibrator(Context context){
        Vibrator vibrator;
        //震动规则(不震动时间,震动时间,不震动时间,震动时间.......)
        long[] pattern = {0, 50, 20, 50};
        VibratorManager vibratorManager;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vibratorManager = (VibratorManager)context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vibratorManager.getDefaultVibrator();
        } else {
            vibrator = (Vibrator)context.getSystemService(VIBRATOR_SERVICE);
        }
        // repeat -1 不重复  0一直震动
        vibrator.vibrate(pattern,-1);
    }

    public static String getNetworkTime(String webUrl){
        try {
            URL url = new URL(webUrl);
            URLConnection conn = url.openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
            conn.connect();
            long dateL = conn.getDate();
            Date date = new Date(dateL);
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            return dateFormat.format(date);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    public static String getNetworkTimeChuo(String webUrl){
        try {
            URL url = new URL(webUrl);
            URLConnection conn = url.openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
            conn.connect();
            long dateL = conn.getDate();
            Date date = new Date(dateL);
            //            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            return String.valueOf(date.getTime());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }


    //不同的类型要区别获取，以下是布尔类型的
    public static boolean getAppMetaDataBoolean(Context context, String metaName, boolean defaultValue) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            String string = info.metaData.getString("");
            //application标签下用getApplicationinfo，
            // 如果是activity下的用getActivityInfo
            boolean value = context.getPackageManager()
                    .getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA)
                    .metaData.getBoolean(metaName, defaultValue);
            Log.e("meta-data", metaName + " = " + value);
            return value;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return defaultValue;
        }
    }


    private static HashMap<String, List<String>> hashMap = new HashMap<String, List<String>>() {
        {
            put("Xiaomi", Arrays.asList(
                    "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",//MIUI10_9.8.1(9.0)
                    "com.miui.securitycenter"
            ));

            put("samsung", Arrays.asList(
                    "com.samsung.android.sm_cn/com.samsung.android.sm.ui.ram.AutoRunActivity",
                    "com.samsung.android.sm_cn/com.samsung.android.sm.ui.appmanagement.AppManagementActivity",
                    "com.samsung.android.sm_cn/com.samsung.android.sm.ui.cstyleboard.SmartManagerDashBoardActivity",
                    "com.samsung.android.sm_cn/.ui.ram.RamActivity",
                    "com.samsung.android.sm_cn/.app.dashboard.SmartManagerDashBoardActivity",

                    "com.samsung.android.sm/com.samsung.android.sm.ui.ram.AutoRunActivity",
                    "com.samsung.android.sm/com.samsung.android.sm.ui.appmanagement.AppManagementActivity",
                    "com.samsung.android.sm/com.samsung.android.sm.ui.cstyleboard.SmartManagerDashBoardActivity",
                    "com.samsung.android.sm/.ui.ram.RamActivity",
                    "com.samsung.android.sm/.app.dashboard.SmartManagerDashBoardActivity",

                    "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
                    "com.samsung.android.sm_cn",
                    "com.samsung.android.sm"
            ));


            put("HUAWEI", Arrays.asList(
                    "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",//EMUI9.1.0(方舟,9.0)
                    "com.huawei.systemmanager/.appcontrol.activity.StartupAppControlActivity",
                    "com.huawei.systemmanager/.optimize.process.ProtectActivity",
                    "com.huawei.systemmanager/.optimize.bootstart.BootStartActivity",
                    "com.huawei.systemmanager"//最后一行可以写包名, 这样如果签名的类路径在某些新版本的ROM中没找到 就直接跳转到对应的安全中心/手机管家 首页.
            ));

            put("vivo", Arrays.asList(
                    "com.iqoo.secure/.ui.phoneoptimize.BgStartUpManager",
                    "com.iqoo.secure/.safeguard.PurviewTabActivity",
                    "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",
                    //                    "com.iqoo.secure/.ui.phoneoptimize.AddWhiteListActivity", //这是白名单, 不是自启动
                    "com.iqoo.secure",
                    "com.vivo.permissionmanager"
            ));

            put("Meizu", Arrays.asList(
                    "com.meizu.safe/.permission.SmartBGActivity",//Flyme7.3.0(7.1.2)
                    "com.meizu.safe/.permission.PermissionMainActivity",//网上的
                    "com.meizu.safe"
            ));

            put("OPPO", Arrays.asList(
                    "com.coloros.safecenter/.startupapp.StartupAppListActivity",
                    "com.coloros.safecenter/.permission.startup.StartupAppListActivity",
                    "com.oppo.safe/.permission.startup.StartupAppListActivity",
                    "com.coloros.oppoguardelf/com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
                    "com.coloros.safecenter/com.coloros.privacypermissionsentry.PermissionTopActivity",
                    "com.coloros.safecenter",
                    "com.oppo.safe",
                    "com.coloros.oppoguardelf"
            ));

            put("oneplus", Arrays.asList(
                    "com.oneplus.security/.chainlaunch.view.ChainLaunchAppListActivity",
                    "com.oneplus.security"
            ));
            put("letv", Arrays.asList(
                    "com.letv.android.letvsafe/.AutobootManageActivity",
                    "com.letv.android.letvsafe/.BackgroundAppManageActivity",//应用保护
                    "com.letv.android.letvsafe"
            ));
            put("zte", Arrays.asList(
                    "com.zte.heartyservice/.autorun.AppAutoRunManager",
                    "com.zte.heartyservice"
            ));
            //金立
            put("F", Arrays.asList(
                    "com.gionee.softmanager/.MainActivity",
                    "com.gionee.softmanager"
            ));

            //以下为未确定(厂商名也不确定)
            put("smartisanos", Arrays.asList(
                    "com.smartisanos.security/.invokeHistory.InvokeHistoryActivity",
                    "com.smartisanos.security"
            ));
            //360
            put("360", Arrays.asList(
                    "com.yulong.android.coolsafe/.ui.activity.autorun.AutoRunListActivity",
                    "com.yulong.android.coolsafe"
            ));
            //360
            put("ulong", Arrays.asList(
                    "com.yulong.android.coolsafe/.ui.activity.autorun.AutoRunListActivity",
                    "com.yulong.android.coolsafe"
            ));
            //酷派
            put("coolpad"/*厂商名称不确定是否正确*/, Arrays.asList(
                    "com.yulong.android.security/com.yulong.android.seccenter.tabbarmain",
                    "com.yulong.android.security"
            ));
            //联想
            put("lenovo"/*厂商名称不确定是否正确*/, Arrays.asList(
                    "com.lenovo.security/.purebackground.PureBackgroundActivity",
                    "com.lenovo.security"
            ));
            put("htc"/*厂商名称不确定是否正确*/, Arrays.asList(
                    "com.htc.pitroad/.landingpage.activity.LandingPageActivity",
                    "com.htc.pitroad"
            ));
            //华硕
            put("asus"/*厂商名称不确定是否正确*/, Arrays.asList(
                    "com.asus.mobilemanager/.MainActivity",
                    "com.asus.mobilemanager"
            ));

        }
    };

    public static void startToAutoStartSetting(Context context) {
        Log.e("Util", "******************当前手机型号为：" + Build.MANUFACTURER);

        Set<Map.Entry<String, List<String>>> entries = hashMap.entrySet();
        boolean has = false;
        for (Map.Entry<String, List<String>> entry : entries) {
            String manufacturer = entry.getKey();
            List<String> actCompatList = entry.getValue();
            if (Build.MANUFACTURER.equalsIgnoreCase(manufacturer)) {
                for (String act : actCompatList) {
                    try {
                        Intent intent;
                        if (act.contains("/")) {
                            intent = new Intent();
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            ComponentName componentName = ComponentName.unflattenFromString(act);
                            intent.setComponent(componentName);
                        } else {
                            //找不到? 网上的做法都是跳转到设置... 这基本上是没意义的 基本上自启动这个功能是第三方厂商自己写的安全管家类app
                            //所以我是直接跳转到对应的安全管家/安全中心
                            intent = context.getPackageManager().getLaunchIntentForPackage(act);
                        }
                        context.startActivity(intent);
                        has = true;
                        break;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        if (!has) {
            Toast.makeText(context, "兼容方案", Toast.LENGTH_SHORT).show();
            try {
                Intent intent = new Intent();
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.setAction("android.settings.APPLICATION_DETAILS_SETTINGS");
                intent.setData(Uri.fromParts("package", context.getPackageName(), null));
                context.startActivity(intent);
            } catch (Exception e) {
                e.printStackTrace();
                Intent intent = new Intent(Settings.ACTION_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
        }


    }

    //===

    public static void gotoPermission(Context context) {
        String brand = Build.BRAND;//手机厂商
        if (TextUtils.equals(brand.toLowerCase(), "blackshark") || TextUtils.equals(brand.toLowerCase(), "Xiaomi")) {
            gotoMiuiPermission(context);//小米
        } else if (TextUtils.equals(brand.toLowerCase(), "meizu")) {
            gotoMeizuPermission(context);
        } else if (TextUtils.equals(brand.toLowerCase(), "HUAWEI") || TextUtils.equals(brand.toLowerCase(), " HONOR")) {
            gotoHuaweiPermission(context);
        } else {
            context.startActivity(getAppDetailSettingIntent(context));
        }
    }


    /**
     * 跳转到miui的权限管理页面
     */
    private static void gotoMiuiPermission(Context context) {
        try { // MIUI 8
            Intent localIntent = new Intent("miui.intent.action.APP_PERM_EDITOR");
            localIntent.setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity");
            localIntent.putExtra("extra_pkgname", context.getPackageName());
            context.startActivity(localIntent);
        } catch (Exception e) {
            try { // MIUI 5/6/7
                Intent localIntent = new Intent("miui.intent.action.APP_PERM_EDITOR");
                localIntent.setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity");
                localIntent.putExtra("extra_pkgname", context.getPackageName());
                context.startActivity(localIntent);
            } catch (Exception e1) { // 否则跳转到应用详情
                context.startActivity(getAppDetailSettingIntent(context));
            }
        }
    }

    /**
     * 跳转到魅族的权限管理系统
     */
    private static void gotoMeizuPermission(Context context) {
        try {
            Intent intent = new Intent("com.meizu.safe.security.SHOW_APPSEC");
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            intent.putExtra("packageName", App_ID);
            context.startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
            context.startActivity(getAppDetailSettingIntent(context));
        }
    }

    /**
     * 华为的权限管理页面
     */
    private static void gotoHuaweiPermission(Context context) {
        try {
            Intent intent = new Intent();
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ComponentName comp = new ComponentName("com.huawei.systemmanager", "com.huawei.permissionmanager.ui.MainActivity");//华为权限管理
            intent.setComponent(comp);
            context.startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
            context.startActivity(getAppDetailSettingIntent(context));
        }

    }

    /**
     * 获取应用详情页面intent（如果找不到要跳转的界面，也可以先把用户引导到系统设置页面）
     */
    private static Intent getAppDetailSettingIntent(Context context) {
        Intent localIntent = new Intent();
        localIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        localIntent.setAction("android.settings.APPLICATION_DETAILS_SETTINGS");
        localIntent.setData(Uri.fromParts("package", context.getPackageName(), null));

        return localIntent;
    }

    private static TextToSpeech tts;



    //==通知栏红包提醒
    private static TextToSpeech tts2;


    //===============
    private static TextToSpeech tts3;



    //===============


    //==

    //==进入游戏模式提醒
    private static TextToSpeech tts4;

    public static void play4(Context cts) {
        tts4 = new TextToSpeech(cts, new listener4());
    }

    private static class listener4 implements TextToSpeech.OnInitListener {


        @Override
        public void onInit(int status) {
            if (status == TextToSpeech.SUCCESS) {
                //设置播放语言
                int result = tts4.setLanguage(Locale.CHINESE);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    //                    Toast.makeText(MyApplication.instance(), "当前手机不支持语音播报", Toast.LENGTH_SHORT).show();
                } else if (result == TextToSpeech.LANG_AVAILABLE) {
                    //初始化成功之后才可以播放文字
                    //否则会提示“speak failed: not bound to tts engine
                    //TextToSpeech.QUEUE_ADD会将加入队列的待播报文字按顺序播放
                    //TextToSpeech.QUEUE_FLUSH会替换原有文字
                    // api >= 33
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // 设置音量和频道参数
                        Bundle params = new Bundle();
                        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f); // 设置音量为0.8
//                        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC); // 设置频道为音乐频道
                        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM); // 设置频道为闹钟频道
                        tts4.speak("秒启动已进入游戏模式",TextToSpeech.QUEUE_FLUSH,params,"1");

                    }else {
                        tts4.speak("秒启动已进入游戏模式", TextToSpeech.QUEUE_FLUSH, null);

                    }
                }

            } else {
                Log.e("TAG", "初始化失败");
            }

        }

        public void stopTTS4() {
            if (tts4 != null) {
                tts4.shutdown();
                tts4.stop();
                tts4 = null;
            }
        }


    }


    //==

    //==退出游戏模式提醒
    private static TextToSpeech tts5;

    public static void play5(Context cts) {
        tts5 = new TextToSpeech(cts, new listener5());
    }

    private static class listener5 implements TextToSpeech.OnInitListener {


        @Override
        public void onInit(int status) {
            if (status == TextToSpeech.SUCCESS) {
                //设置播放语言
                int result = tts5.setLanguage(Locale.CHINESE);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    //                    Toast.makeText(MyApplication.instance(), "当前手机不支持语音播报", Toast.LENGTH_SHORT).show();
                } else if (result == TextToSpeech.LANG_AVAILABLE) {
                    //初始化成功之后才可以播放文字
                    //否则会提示“speak failed: not bound to tts engine
                    //TextToSpeech.QUEUE_ADD会将加入队列的待播报文字按顺序播放
                    //TextToSpeech.QUEUE_FLUSH会替换原有文字
                    // api >= 33
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        // 设置音量和频道参数
                        Bundle params = new Bundle();
                        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f); // 设置音量为0.8
                        //                        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC); // 设置频道为音乐频道
                        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM); // 设置频道为闹钟频道
                        tts5.speak("秒启动已退出游戏模式",TextToSpeech.QUEUE_FLUSH,params,"1");

                    }else {
                        tts5.speak("秒启动已退出游戏模式", TextToSpeech.QUEUE_FLUSH, null);

                    }


                }

            } else {
                Log.e("TAG", "初始化失败");
            }

        }

        public void stopTTS5() {
            if (tts5 != null) {
                tts5.shutdown();
                tts5.stop();
                tts5 = null;
            }
        }


    }


    //==


    //将时间戳转换为时间

    public static String stampToTime(String s) throws Exception{
        String res;

        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        long lt = new Long(s);

        //将时间戳转换为时间

        Date date = new Date(lt);

        //将时间调整为yyyy-MM-dd HH:mm:ss时间样式

        res = simpleDateFormat.format(date);

        return res;

    }






    //王者荣耀输入x, y坐标模拟点击事件
    @TargetApi(Build.VERSION_CODES.N)
    public static boolean performXY(AccessibilityService service, float x, float y){
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 20));
        GestureDescription gestureDescription = builder.build();
        return service.dispatchGesture(gestureDescription, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                //Log.i(ConstantsSend.TAG, "onCompleted: completed");
            }
            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                //Log.i(ConstantsSend.TAG, "onCancelled: cancelled");
            }
        }, null);
    }


}
