package com.google.android.accessibility.ext.utils

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import androidx.annotation.RequiresApi
import com.android.accessibility.ext.BuildConfig
import com.google.android.accessibility.ext.activity.AliveFGService
import com.google.android.accessibility.ext.utils.MMKVConst.KEEP_ALIVE_BY_FLOATINGWINDOW
import com.google.android.accessibility.ext.utils.MMKVConst.KEEP_ALIVE_BY_NOTIFICATION

import com.google.android.accessibility.ext.utils.MMKVConst.TASKHIDE_LIST
import com.google.android.accessibility.ext.utils.MMKVConst.UPDATE_SCOPE
import com.google.android.accessibility.ext.utils.MMKVConst.UPDATE_VALUE
import com.google.android.accessibility.selecttospeak.SelectToSpeakServiceAbstract
import org.json.JSONArray
import java.util.Objects

/**
 * PackageName :
 * Author :
 * Date : 2025/7/16
 * Time : 10:29
 * Introduction : 用于 Library 内拿到 applicationContext
 */
class LibCtxProvider : ContentProvider() {
    private var handler: Handler? = null
    companion object {
        //全局 Application
        lateinit var appContext: Context
        lateinit var contentProviderAuthority: String
        var appMyName: String =""
        var appVersionName: String =""
        var appVersionCode: Long = -1L
        var appBuildTime: Long  = -1L
        // 时间单位
        val oneMinuteInMillis: Long = 60000        // 1分钟 = 60,000毫秒
        val oneHourInMillis: Long = 3600000        // 1小时 = 3,600,000毫秒
        val oneDayInMillis: Long = 86400000        // 1天 = 86,400,000毫秒
        val oneMonthInMillis: Long = 2592000000    // 1个月(30天) = 2,592,000,000毫秒
        val threeMonthsInMillis: Long = 7776000000  // 3个月(90天) = 7,776,000,000毫秒
        val sixMonthsInMillis: Long = 15552000000  // 6个月(180天) = 15,552,000,000毫秒
        val oneYearInMillis: Long = 31536000000  // 1年(365天) = 31,536,000,000毫秒


    }
    init {
        handler = Handler(Looper.getMainLooper())
    }

    /**
     * 在application的oncreate之前，attachBaseContext之后
     * 因为在attachBaseContext中拿不到全局的applicationContext
     */
    override fun onCreate(): Boolean {
        appContext = context?.applicationContext!!

        (appContext as? Application)?.registerActivityLifecycleCallbacks(object :
            Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(activity: Activity) {
            }

            override fun onActivityStarted(activity: Activity) {
            }

            override fun onActivityDestroyed(activity: Activity) {
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
            }

            override fun onActivityStopped(activity: Activity) {
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            }

            override fun onActivityResumed(activity: Activity) {
            }

        })
        //定义 contentProvider  Authority
        //"content://" 是 必须的，是 标准 的 URI 前缀
        contentProviderAuthority = "content://" + appContext.packageName+".xpqutilsProvider"
        //初始化SharedPreferences
        SPUtils.init(appContext)

        // 1. 版本信息（系统可拿）
        val pm = appContext.packageManager
        val info = pm.getPackageInfo(appContext.packageName, 0)
        appVersionName = info.versionName.toString()
        appVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        // 2. buildTime（只能从宿主 BuildConfig 注入，拿不到就降级）
        appBuildTime = tryGetAppBuildTime(appContext)
        appMyName = getAppName(appContext)

        return true
    }

    private fun tryGetAppBuildTime(context: Context): Long {
        return try {
            val ai = context.packageManager
                .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)

            ai.metaData?.getLong("APP_BUILD_TIME", -1L) ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }
    private fun getAppName(context: Context): String {
        val pm = context.packageManager
        val ai = context.applicationInfo

        return when {
            ai.nonLocalizedLabel != null ->
                ai.nonLocalizedLabel.toString()

            ai.labelRes != 0 ->
                context.getString(ai.labelRes)

            else ->
                context.packageName // 最差兜底
        }
    }


    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        return null
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {

        if (values != null) {
            updateKeepAlive(values!!)
        }
        return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        return -1
    }

    override fun getType(uri: Uri): String? {
        return null
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private fun updateKeepAlive(values: ContentValues) {
        val updateScope = values.getAsString(UPDATE_SCOPE)
        val value = values.getAsBoolean(UPDATE_VALUE)




        if (TextUtils.isEmpty(updateScope) || Objects.isNull(value)) {
            return
        }

        var service: Service? = null
        var isAcc = false

        if (SelectToSpeakServiceAbstract.instance != null){
            service = SelectToSpeakServiceAbstract.instance
            isAcc = true
        } else if (AliveFGService.fg_instance != null){
            service = AliveFGService.fg_instance
            isAcc = false
        }

        if (TextUtils.equals(updateScope, KEEP_ALIVE_BY_NOTIFICATION)) {

            AliveUtils.keepAliveByNotification_CLS(service,value,null)
        }
        if (TextUtils.equals(updateScope, KEEP_ALIVE_BY_FLOATINGWINDOW)) {
            handler!!.post {

                AliveUtils.keepAliveByFloatingWindow(service,value)

            }
        }

        if (TextUtils.equals(updateScope, MMKVConst.KEY_OPEN_YIN_CANG)) {
            handler!!.post {

                val jsonStr = values.getAsString(TASKHIDE_LIST)
                val jsonArray = JSONArray(jsonStr)
                val resultList = mutableListOf<String>()
                for (i in 0 until jsonArray.length()) {
                    resultList.add(jsonArray.getString(i))
                }
                AliveUtils.setExcludeFromRecents(value,resultList)
                // resultList 标为永久隐藏
                //RecentsUtils.setExcludeFromRecents(exclude = value, targetActivities = resultList)


            }
        }

        if (TextUtils.equals(updateScope, MMKVConst.KEY_OPEN_YIN_CANG_PLUS)) {
            if (!AliveUtils.getKeepAliveByTaskHide()){
                //普通的未开启,则plus无论怎样也不执行
               return
            }
            handler!!.post {

                val jsonStr = values.getAsString(TASKHIDE_LIST)
                val jsonArray = JSONArray(jsonStr)
                val resultList = mutableListOf<String>()
                for (i in 0 until jsonArray.length()) {
                    resultList.add(jsonArray.getString(i))
                }
                AliveUtils.setExcludeFromRecentsPlus(value,resultList)
                // resultList 标为永久隐藏



            }
        }


    }
}