package com.lygttpod.android.auto.wx.page.qunfa

import com.google.android.accessibility.ext.acc.clickById
import com.google.android.accessibility.ext.acc.clickByIdAndText
import com.google.android.accessibility.ext.acc.findById
import com.google.android.accessibility.ext.task.retryCheckTaskWithLog
import com.lygttpod.android.auto.wx.data.NodeInfo
import com.lygttpod.android.auto.wx.page.IPage
import com.lygttpod.android.auto.wx.service.wxAccessibilityService
import com.lygttpod.android.auto.wx.version.nodeProxy

object WX2XuanZePYPage : IPage {

    interface Nodes {
//        val xiangceRecyclerViewNode: NodeInfo //后续可能会从listview变成recyclerView
        val XZPY_TagimportNode: NodeInfo //com.tencent.mm:id/ci2
        val XZPY_XuanZhongNode: NodeInfo //com.tencent.mm:id/g6_


        companion object : Nodes by nodeProxy()
    }

    override fun pageClassName() = "com.tencent.mm.ui.mvvm.MvvmContactListUI"

    override fun pageTitleName() = "选择朋友页"

    override fun isMe(): Boolean {
        return wxAccessibilityService?.findById(Nodes.XZPY_TagimportNode.nodeId) != null
    }

    suspend fun checkInPage(): Boolean {
        return delayAction {
            retryCheckTaskWithLog("检查当前是否打开选择朋友页") {
                isMe()
            }
        }
    }


    /**
     * 点击从标签导入
     */
    suspend fun clickTagimport(): Boolean {
        return delayAction(delayMillis = 1000) {
            retryCheckTaskWithLog("点击选择朋友页的【从标签导入】按钮") {
                wxAccessibilityService.clickByIdAndText(
                    Nodes.XZPY_TagimportNode.nodeId,
                    Nodes.XZPY_TagimportNode.nodeText
                )
            }
        }
    }


    /**
     * 点击【选中()】按钮
     */
    suspend fun clickSelect(): Boolean {
        return delayAction(delayMillis = 1000) {
            retryCheckTaskWithLog("点击【选中()】按钮") {
                wxAccessibilityService.clickById(Nodes.XZPY_XuanZhongNode.nodeId)
//                wxAccessibilityService.clickByText(Nodes.XZPY_XuanZhongNode.nodeText)
            }
        }
    }




}