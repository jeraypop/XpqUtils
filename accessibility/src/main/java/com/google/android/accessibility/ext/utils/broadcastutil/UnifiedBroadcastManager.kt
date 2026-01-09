package com.google.android.accessibility.ext.utils.broadcastutil

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.accessibility.ext.utils.KeyguardUnLock.sendLog
import java.util.Collections

/**
 * 统一广播治理中心
 *
 * 功能：
 * - 广播通道唯一生效
 * - 优先级抢占（无障碍 > 通知监听 > Service > Activity）
 * - 生命周期自动清理
 * - 通用广播（不限屏幕）
 */
object UnifiedBroadcastManager {

    private val channels =
        Collections.synchronizedMap(HashMap<String, ManagedBroadcast>())

    const val CHANNEL_SCREEN = "SCREEN_STATE" //屏幕广播 Channel
    const val XPQ_SCREEN_TEST = "xpq.screen.test"
    @JvmField
    val screenFilter = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_OFF)    // 息屏
        addAction(Intent.ACTION_SCREEN_ON)     // 亮屏（可选）
        addAction(Intent.ACTION_USER_PRESENT)  // 解锁完成
        addAction(XPQ_SCREEN_TEST)  // 调试用
    }


    /**
     * 注册广播（支持抢占）
     */
    @JvmStatic
    @JvmOverloads
    fun register(
        channel: String,
        owner: Any,
        ownerType: BroadcastOwnerType,
        context: Context,
        receiver: BroadcastReceiver,
        filter: IntentFilter,
        lifecycleOwner: LifecycleOwner? = null,
        exported: Boolean = true
    ): Boolean {

        val managed = channels.getOrPut(channel) {
            ManagedBroadcast(channel)
        }

        synchronized(managed) {

            // 已是当前 owner
            if (managed.owner === owner) return true

            // 优先级不足，拒绝注册
            if (ownerType.priority < managed.ownerType.priority) {
                return false
            }

            // 抢占旧的
            managed.receiver?.let { oldReceiver ->
                managed.context?.let { oldContext ->
                    BroadCastReciverHelper.safeUnregisterReceiver(
                        oldContext,
                        oldReceiver
                    )
                }
            }


            val success = BroadCastReciverHelper.safeRegisterReceiver(
                context = context,
                receiver = receiver,
                filter = filter,
                exported = exported
            )

            if (!success) return false

            managed.owner = owner
            managed.ownerType = ownerType
            managed.receiver = receiver
            managed.context = context

            // 生命周期自动解绑（Activity / Fragment）
            lifecycleOwner?.lifecycle?.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onDestroy(owner: LifecycleOwner) {
                        //Log.e("监听屏幕啊", "生命周期自动解绑：onDestroy" )
                        //unregister(channel, owner, context)
                    }
                }
            )

            Log.e("监听屏幕啊", "注册屏幕广播：${managed.ownerType}" )
            //sendLog("注销屏幕广播：${managed.ownerType}")
            return true
        }
    }

    /**
     * 主动注销
     */
    @JvmStatic
    fun unregister(
        channel: String,
        owner: Any,
        context: Context
    ) {
        val managed = channels[channel] ?: return

        synchronized(managed) {
            if (managed.owner !== owner) return
            Log.e("监听屏幕啊", "注销屏幕广播：${managed.ownerType}" )
            sendLog("注销屏幕广播：${managed.ownerType}")
            managed.context?.let {
                managed.receiver?.let { r ->
                    BroadCastReciverHelper.safeUnregisterReceiver(it, r)
                }
            }


            managed.owner = null
            managed.ownerType = BroadcastOwnerType.NONE
            managed.receiver = null
            managed.context = null
        }
    }

    /**
     * 当前通道拥有者（调试用）
     */
    @JvmStatic
    fun currentOwnerType(channel: String): BroadcastOwnerType {
        return channels[channel]?.ownerType ?: BroadcastOwnerType.NONE
    }
}
