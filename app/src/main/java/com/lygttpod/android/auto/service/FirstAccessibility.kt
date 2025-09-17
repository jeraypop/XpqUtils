package com.lygttpod.android.auto.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.MutableLiveData
import com.google.android.accessibility.ext.toast
import com.google.android.accessibility.ext.utils.AliveUtils.keepAliveByFloatingWindow
import com.google.android.accessibility.ext.utils.AliveUtils.keepAliveByNotification_CLS
import com.google.android.accessibility.notification.AccessibilityNInfo
import com.google.android.accessibility.selecttospeak.SelectToSpeakServiceAbstract
import com.google.android.accessibility.selecttospeak.accessibilityService
import java.util.concurrent.Executors

//import com.lygttpod.android.auto.wx.helper.ToastUtil.keepAliveByNotification_CLS
import java.util.concurrent.atomic.AtomicBoolean


 /*
 * 第一次继承
 *
 * */
open class FirstAccessibility : SelectToSpeakServiceAbstract() {

    companion object {
        var isInWXApp = AtomicBoolean(false)
    }

     // 自己的线程池处理长耗时任务（可以是上传、复杂解析、长时间点击重试等）
     private val worker = Executors.newFixedThreadPool(2)

    override fun targetPackageName() = "com.tencent.mm"



    override fun onServiceConnected() {
        super.onServiceConnected()

    }

    override fun asyncHandleAccessibilityEvent(event: AccessibilityEvent) {
//        HBTaskHelper.hbTask(event)
        val s = getTextById(this, "com.tencent.mm:id/obn")
//        Log.e("文本内容", "=: "+s )
    }

     override fun asyncHandle_WINDOW_STATE_CHANGED(
         root: AccessibilityNodeInfo,
         nodeInfoSet: MutableSet<AccessibilityNodeInfo>,
         pkgName: String,
         className: String
     ) {
         // 场景：我们希望对每个节点做一个长耗时操作并在完成后释放副本

         // 方法一： 使用 submitNodeForChild（父类会再为你复制一份并记录 ownership）
         for (nodeCopy in nodeInfoSet) {
             // nodeCopy 已是父类复制的副本；但 submitNodeForChild 会再复制一份（安全但多一次复制）
             submitNodeForChild(nodeCopy) { takenCopy ->
                 // 这个 lambda 在父类 executor 线程同步调用，
                 // 一旦你在 lambda 里返回 true，父类会把 takenCopy 登记到 ownershipMap（等待你 later release）
                 // 你应该立即把 takenCopy 交给其他线程池处理，然后在处理完调用 releaseNode(takenCopy)
                 worker.execute {
                     try {
                         // 长耗时处理示例
                         val id = takenCopy.viewIdResourceName
                         val txt = takenCopy.text?.toString()
                         // 假装上传或大量解析
                         Thread.sleep(500L) // 示例：耗时任务
                         Log.d("AsyncWindowHandler", "uploaded node id=$id text=${txt ?: "null"}")
                     } catch (e: Throwable) {
                         Log.w("AsyncWindowHandler", "worker task error", e)
                     } finally {
                         // 处理完必须释放（告诉父类回收）
                         releaseNode(takenCopy)
                     }
                 }
                 // 返回 true 表示我们接管了 takenCopy（父类不会回收）
                 true
             }
             // 父类会继续处理下一个 nodeCopy
         }

         // 方法二（更高效，避免二次 copy）： 使用 claimNodeDirectly（如果你把上面函数加入基类）
         // 说明：claimNodeDirectly 直接把传入的 nodeCopy 登记为被接管（避免再复制一份）。
         // 注意：只有在你**确定** nodeCopy 是父类传入的副本或是你自己通过 copyNodeCompat 得到的副本时才可用。
         for (nodeCopy in nodeInfoSet) {
             // try to claim directly (no additional copy)
             val claimed = claimNodeDirectly(nodeCopy)
             if (claimed) {
                 // 把 nodeCopy 交给 worker，worker 最终必须调用 releaseNode(nodeCopy)
                 worker.execute {
                     try {
                         val id = nodeCopy.viewIdResourceName
                         val txt = nodeCopy.text?.toString()
                         // do long work
                         Thread.sleep(500L)
                         Log.d("AsyncWindowHandler", "claimed process done id=$id")
                     } catch (e: Throwable) {
                         Log.w("AsyncWindowHandler", "worker claimed error", e)
                     } finally {
                         // release so parent can recycle
                         releaseNode(nodeCopy)
                     }
                 }
             } else {
                 // claim 失败，可能已经被其它接管或重复请求；根据情况回退或忽略
                 Log.w("AsyncWindowHandler", "claimNodeDirectly failed for node $nodeCopy")
             }
         }

         // 关键注意：如果你使用 claimNodeDirectly，父类在 finally 中**不会**重复回收这些 node，
         // 因为它们已被登记到 ownershipMap。releaseNode 必须被调用一次来回收。
     }



     override fun onInterrupt() {
        super.onInterrupt()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        return super.onUnbind(intent)
    }



    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }






    fun getTextById(service: AccessibilityService, id: String?): String {
        var text = ""
        val nodeInfo = service?.rootInActiveWindow ?: return text
        val nodeInfoList = nodeInfo.findAccessibilityNodeInfosByViewId(id.toString())
        if (nodeInfoList.isNotEmpty()){
              if (nodeInfoList[0].text != null) {
                  text = nodeInfoList[0].text.toString()
              }
        }

        return text

    }

}