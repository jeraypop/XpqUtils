package com.lygttpod.android.auto;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;


import com.lygttpod.android.auto.wx.helper.ToastUtil;
import com.lygttpod.android.auto.wx.service.WXAccessibility;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.MutableLiveData;
import xpq.friend.R;


import static android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS;
import static com.google.android.accessibility.ext.ContextExtKt.toast;
import static com.lygttpod.android.auto.ForegroundService.mForegroundService;
import static com.lygttpod.android.auto.ForegroundService.serviceIsLive;
import static com.lygttpod.android.auto.ForegroundService14.mForegroundService14;
import static com.lygttpod.android.auto.ForegroundService14.serviceIsLive14;


public class QuanXianActivity extends AppCompatActivity {
    private MutableLiveData<Boolean> mAppPermission;
    private MutableLiveData<Boolean> mAccessibilityPermission;
    private MutableLiveData<Boolean> mPowerOptimization;
    Boolean           isOpen=false;
    private     DevicePolicyManager devicePolicyManager;
    private PackageManager      packageManager;
    private PowerManager powerManager;


    @Override
    public Resources getResources() {
        Resources resources = super.getResources();
        Configuration config = new Configuration();
        config.setToDefaults();
        resources.updateConfiguration(config, resources.getDisplayMetrics());
        //        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        //            createConfigurationContext(config);
        //        }else{
        //            resources.updateConfiguration(config, resources.getDisplayMetrics());
        //        }
        return resources;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onResume() {
        super.onResume();


/*
        Switch autobaohuo = findViewById(R.id.autobaohuo);
        autobaohuo.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                MyApplication.Companion.getSpwang().edit().putBoolean("autobaohuoison",isChecked).apply();

            }
        });
        autobaohuo.setChecked(MyApplication.Companion.getSpwang().getBoolean("autobaohuoison", false));

*/


    }



    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onRestart() {
        super.onRestart();
    }

    @Override
    protected void onPause() {

        super.onPause();
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quan_xian);

        PowerManager pmwl = (PowerManager) getSystemService(Context.POWER_SERVICE);
        @SuppressLint("InvalidWakeLockTag")
        PowerManager.WakeLock wakeLock = pmwl.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyWakelockTag");


        powerManager = getSystemService(PowerManager.class);
        packageManager = getPackageManager();
        devicePolicyManager = getSystemService(DevicePolicyManager.class);
        setFinishOnTouchOutside(true);

        //教程指导
        TextView baohuohelp = findViewById(R.id.text_instructions);
        baohuohelp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intenti2 = new Intent();
                intenti2.setAction("android.intent.action.VIEW");
                Uri content_url2 = Uri.parse("https://mp.weixin.qq.com/s/umGL41SgEapebNA8Pz1Tjw");
                intenti2.setData(content_url2);
                startActivity(intenti2);
            }
        });

        final Drawable drawableYes = ContextCompat.getDrawable(this, R.drawable.ic_open);
        final Drawable drawableNo = ContextCompat.getDrawable(this, R.drawable.ic_close);
        //图形开关监测

        //电池优化
        final ImageView imagePowerPermission = findViewById(R.id.image_power_permission);
        String packageName = QuanXianActivity.this.getPackageName();
        PowerManager pm = (PowerManager) QuanXianActivity.this.getSystemService(Context.POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(packageName)){
            imagePowerPermission.setImageDrawable(drawableYes);

        }else {
            imagePowerPermission.setImageDrawable(drawableNo);
        }

        //前台服务
        final ImageView imageqiantaifuwuPermission = findViewById(R.id.image_qiantaifuwu_permission);

        if (serviceIsLive14){
            imageqiantaifuwuPermission.setImageDrawable(drawableYes);

        }else {
            imageqiantaifuwuPermission.setImageDrawable(drawableNo);
        }

        //设备管理员
/*        final ImageView imageguanliyuanPermission = findViewById(R.id.image_guanliyuan_permission);

        if (devicePolicyManager.isAdminActive(new ComponentName(instance(), MyDeviceAdminReceiver.class))){
            imageguanliyuanPermission.setImageDrawable(drawableYes);

        }else {
            imageguanliyuanPermission.setImageDrawable(drawableNo);
        }*/

        //0像素保活
    /*    final ImageView image0xiangsuPermission = findViewById(R.id.image_0xiangsu_permission);

        if (MyUtils.getKeepAliveByFloatingWindow()){
            image0xiangsuPermission.setImageDrawable(drawableYes);

        }else {
            image0xiangsuPermission.setImageDrawable(drawableNo);
        }*/

        //悬浮窗
/*        final ImageView imagefloatPermission = findViewById(R.id.image_float_permission);

        if (PermissionUtils.checkPermission(this)){
            imagefloatPermission.setImageDrawable(drawableYes);

        }else {
            imagefloatPermission.setImageDrawable(drawableNo);
        }*/

        //====================按钮监测===============================================
        //电池优化
        final ImageButton btPowerPermission = findViewById(R.id.button_power_permission);
        btPowerPermission.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //==
                if (powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
                    Toast.makeText(getApplicationContext(), "忽略电池优化权限已开启", Toast.LENGTH_SHORT).show();
                } else {
                    Intent intentBatteryIgnore = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
                    if (intentBatteryIgnore.resolveActivity(packageManager) != null) {
                        startActivity(intentBatteryIgnore);
                    } else {
                        Toast.makeText(getApplicationContext(), "授权窗口打开失败，请手动打开", Toast.LENGTH_SHORT).show();
                    }
                }
                
                //==

/*                Intent intent = new Intent();
                String packageName = QuanXianActivity.this.getPackageName();
                PowerManager pm = (PowerManager) QuanXianActivity.this.getSystemService(Context.POWER_SERVICE);
                if (pm.isIgnoringBatteryOptimizations(packageName))
                    intent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                else {
                    intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + packageName));
                }
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                QuanXianActivity.this.startActivity(intent);*/

                finish();

                //===
            }
        });

        //前台服务
        final ImageButton btqiantaifuwuPermission = findViewById(R.id.button_qiantaifuwu_permission);
        btqiantaifuwuPermission.setOnClickListener(new View.OnClickListener() {
            @RequiresApi(api = Build.VERSION_CODES.N)
            @Override
            public void onClick(View v) {
                if (getSystemService(NotificationManager.class).areNotificationsEnabled()) {
                    //===

                    //启动服务
                    if(!serviceIsLive14){
                        ToastUtil.toast(getApplicationContext(), "正在开启请稍候!");
                        mForegroundService14 = new Intent(getApplicationContext(), ForegroundService14.class) ;
                        mForegroundService14.putExtra("Foreground", "正在开启请稍候!");
                        // Android 8.0使用startForegroundService在前台启动新服务
                        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
                            startForegroundService(mForegroundService14);
                        }else{
                            startService(mForegroundService14);
                        }

                    }else{
                        ToastUtil.toast(getApplicationContext(), "前台保活服务已开启!");
                    }

                    //===
                }
                else {
                    if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0x01);
                    } else {
//                        startActivityYinCang=false;
                        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                        intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                        if (intent.resolveActivity(packageManager) != null) {
                            startActivity(intent);
                        } else {
                            Toast.makeText(getApplicationContext(), "授权窗口打开失败，请手动打开", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
                finish();
                //===


                //===
            }
        });

        //设备管理员
/*
        final ImageButton btguanliyuanPermission = findViewById(R.id.button_guanliyuan_permission);
        btguanliyuanPermission.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityYinCang=true;
                Long firstInstallTime = getFirstInstallTime(instance());
                long yuDay = 30 - (System.currentTimeMillis() - firstInstallTime) / (24 * 60 * 60 * 1000L);
                String msg ="";
                if (0<=yuDay && yuDay<=30) {
                    msg = "因该权限开启后,部分手机卸载前可能需要先取消该授权,"+"故新用户在 "+yuDay+" 天后,才可以开启此权限!";
                }  else {
                    msg = "该权限开启后,部分手机卸载前可能需要先取消该授权哦!";
                }

                final AlertDialog.Builder normalDialog =  new AlertDialog.Builder(QuanXianActivity.this);
                normalDialog.setIcon(R.drawable.ic_float_app);
                normalDialog.setTitle("温馨提醒");
                normalDialog.setMessage(msg);
                normalDialog.setPositiveButton("开启",  new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {

                        if (0<=yuDay && yuDay<=30) {
                            Toast.makeText(instance(), "新用户在 "+yuDay+" 天后,才可以开启此权限", Toast.LENGTH_SHORT).show();
                        } else {
                            //...To-do
                            ComponentName compMyDeviceAdmin = new ComponentName(instance(), MyDeviceAdminReceiver.class);
                            if (devicePolicyManager.isAdminActive(compMyDeviceAdmin)) {
                                Toast.makeText(instance(), "设备管理器权限已开启", Toast.LENGTH_SHORT).show();
                            } else {
                                Intent intentDeviceAdmin = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                                intentDeviceAdmin.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compMyDeviceAdmin);
                                if (intentDeviceAdmin.resolveActivity(packageManager) != null) {
                                    startActivity(intentDeviceAdmin);
                                } else {
                                    Toast.makeText(instance(), "授权窗口打开失败，请手动打开", Toast.LENGTH_SHORT).show();
                                }
                            }
                            finish();
                        }

                    }
                });
                normalDialog.setNegativeButton("取消",  new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        //...To-do
                        ComponentName devAdminReceiver = new ComponentName(MyApplication.instance(), MyDeviceAdminReceiver.class);
                        DevicePolicyManager dpm = (DevicePolicyManager) MyApplication.instance().getSystemService(Context.DEVICE_POLICY_SERVICE);

                        if (dpm.isAdminActive(devAdminReceiver)) {
                            dpm.removeActiveAdmin(devAdminReceiver);
                            Toast.makeText(MyApplication.instance(),"已取消设备管理员", Toast.LENGTH_SHORT).show();
                        }else {
                            Toast.makeText(MyApplication.instance(),"权限未激活", Toast.LENGTH_SHORT).show();
                        }


                        //                        // 通过程序的报名创建URI
                        //                        Uri packageURI = Uri.parse("package:" + getPackageName());
                        //                        // 创建Intent意图
                        //                        Intent intent = new Intent(Intent.ACTION_DELETE, packageURI);
                        //                        // 执行卸载程序
                        //                        instance().startActivity(intent);

                        finish();
                    }
                });
                // 显示
                normalDialog.show();

                //===


                //===
            }
        });
*/


        //0像素
/*
        final ImageButton bt0xiangsuPermission = findViewById(R.id.button_0xiangsu_permission);
        bt0xiangsuPermission.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //===
                boolean keepAliveByFloatingWindow = !MyUtils.getKeepAliveByFloatingWindow();
                MyUtils.setKeepAliveByFloatingWindow(keepAliveByFloatingWindow);
                MyUtils.requestUpdateKeepAliveByFloatingWindow(keepAliveByFloatingWindow);
                if (MyUtils.getKeepAliveByFloatingWindow()){
                    image0xiangsuPermission.setImageDrawable(drawableYes);

                }else {
                    image0xiangsuPermission.setImageDrawable(drawableNo);
                }
                Toast.makeText(instance(), keepAliveByFloatingWindow ? "0像素已开启" : "0像素已关闭", Toast.LENGTH_SHORT).show();
                //===
            }
        });
*/


        //悬浮窗
/*
        final ImageButton btfloatPermission = findViewById(R.id.button_float_permission);
        btfloatPermission.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityYinCang=true;
                //                Intent intentAlertWindow = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                //                if (intentAlertWindow.resolveActivity(packageManager) != null) {
                //                    startActivity(intentAlertWindow);
                //                } else {
                //                    showMsg("授权窗口打开失败，请手动打开");
                //                }
                //===
                PermissionUtils.requestPermission(QuanXianActivity.this, new OnPermissionResult() {
                    @Override
                    public void permissionResult(boolean b) {
                        if (b){
                            imagefloatPermission.setImageDrawable(drawableYes);

                        }else {
                            imagefloatPermission.setImageDrawable(drawableNo);
                        }
                    }
                });
                //===
            }
        });
*/



        //===自启动
        final ImageButton btqidong = findViewById(R.id.button_power_permission2);
        btqidong.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //自启动管理界面
                Utilshezhi.startToAutoStartSetting(QuanXianActivity.this);
            }
        });
        //==加锁免清理
        final ImageButton btjiasuo = findViewById(R.id.button_power_permission3);
        btjiasuo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //打开最近任务列表
                if (WXAccessibility.Companion.getWXService()==null) {
                    toast(getApplicationContext(),"无障碍服务未开启,请手动进入最近任务列表");
                }else {
                    toast(getApplicationContext(),"在最近任务列表给本软件加锁");
                    WXAccessibility.Companion.getWXService().performGlobalAction(GLOBAL_ACTION_RECENTS);
                }
                //===
            }
        });
        //==设置
        final ImageButton btshezhi = findViewById(R.id.button_power_permission4);
        btshezhi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //打开设置
                Utilshezhi.gotoPermission(QuanXianActivity.this);
                //===
            }
        });


    }




    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {

        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onStop() {
        super.onStop();

    }





}