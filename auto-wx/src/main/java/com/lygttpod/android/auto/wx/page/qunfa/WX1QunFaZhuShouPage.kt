package com.lygttpod.android.auto.wx.page.qunfa

import com.google.android.accessibility.ext.acc.clickById
import com.google.android.accessibility.ext.acc.findById
import com.google.android.accessibility.ext.task.retryCheckTaskWithLog
import com.lygttpod.android.auto.wx.data.NodeInfo
import com.lygttpod.android.auto.wx.page.IPage
import com.lygttpod.android.auto.wx.service.wxAccessibilityService
import com.lygttpod.android.auto.wx.version.nodeProxy

object WX1QunFaZhuShouPage : IPage {

    interface Nodes {
//        val xiangceRecyclerViewNode: NodeInfo //后续可能会从listview变成recyclerView
        val QFZS_ListViewNode: NodeInfo //com.tencent.mm:id/jcf
        val QFZS_NewQunFaNode: NodeInfo //com.tencent.mm:id/jcv


        companion object : Nodes by nodeProxy()
    }

    override fun pageClassName() = "com.tencent.mm.plugin.masssend.ui.MassSendHistoryUI"

    override fun pageTitleName() = "群发助手,新建群发页"

    override fun isMe(): Boolean {
        return wxAccessibilityService?.findById(Nodes.QFZS_ListViewNode.nodeId) != null
    }

    suspend fun checkInPage(): Boolean {
        return delayAction {
            retryCheckTaskWithLog("检查当前是否打开群发助手,新建群发页") {
                isMe()
            }
        }
    }





    /**
     * 点击【新建群发】按钮
     */
    suspend fun clickNewQunFa(): Boolean {
        return delayAction(delayMillis = 1000) {
            retryCheckTaskWithLog("点击【新建群发】按钮") {
                wxAccessibilityService.clickById(Nodes.QFZS_NewQunFaNode.nodeId)
//                wxAccessibilityService.clickByText(Nodes.QFZS_NewQunFaNode.nodeText)
            }
        }
    }




}