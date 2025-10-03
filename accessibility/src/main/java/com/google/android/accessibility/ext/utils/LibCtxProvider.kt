package com.google.android.accessibility.ext.utils

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
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
import com.google.android.accessibility.ext.utils.MMKVConst.UPDATE_SCOPE
import com.google.android.accessibility.ext.utils.MMKVConst.UPDATE_VALUE
import com.google.android.accessibility.selecttospeak.SelectToSpeakServiceAbstract
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
        val appBuildTime: Long = BuildConfig.BUILD_TIME
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
        return true
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
    }
}