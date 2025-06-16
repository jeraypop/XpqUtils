package com.lygttpod.android.auto.wx.helper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import com.lygttpod.android.auto.wx.R


object ToastUtil {

    @JvmStatic
    fun toast(context: Context, @StringRes int: Int) = Toast.makeText(context, int, Toast.LENGTH_LONG).show()

    @JvmStatic
    fun toast(context: Context, msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    @JvmStatic
    fun toast(context: Context, msg: String, time: Int) = Toast.makeText(context, msg, time).show()



}