package com.lygttpod.android.auto.wx.page.qunfa

import com.google.android.accessibility.ext.acc.clickById
import com.google.android.accessibility.ext.acc.findById
import com.google.android.accessibility.ext.task.retryCheckTaskWithLog
import com.lygttpod.android.auto.wx.data.NodeInfo
import com.lygttpod.android.auto.wx.page.IPage
import com.lygttpod.android.auto.wx.service.wxAccessibilityService
import com.lygttpod.android.auto.wx.version.nodeProxy

object WX3TagImportPage : IPage {

    interface Nodes {

        val TAGDR_daoruNode: NodeInfo //com.tencent.mm:id/fp   导入     android.widget.Button
        val TAGDR_SousuoNode: NodeInfo //com.tencent.mm:id/mmz 搜索  android.widget.TextView


        companion object : Nodes by nodeProxy()
    }

    override fun pageClassName() = "com.tencent.mm.plugin.label.ui.ContactLabelSelectUI"

    override fun pageTitleName() = "从标签导入页"

    override fun isMe(): Boolean {
        return wxAccessibilityService?.findById(Nodes.TAGDR_daoruNode.nodeId) != null
    }

    suspend fun checkInPage(): Boolean {
        return delayAction {
            retryCheckTaskWithLog("检查当前是否打开从标签导入页") {
                isMe()
            }
        }
    }


    /**
     * 点击【搜索】按钮
     */
    suspend fun clickSearch(): Boolean {
        return delayAction(delayMillis = 1000) {
            retryCheckTaskWithLog("点击【搜索】按钮") {
                wxAccessibilityService.clickById(Nodes.TAGDR_SousuoNode.nodeId)
//                wxAccessibilityService.clickByText(Nodes.TAGDR_SousuoNode.nodeText)
            }
        }
    }

    /**
     * 点击【导入】按钮
     */
    suspend fun clickDaoRu(): Boolean {
        return delayAction(delayMillis = 1000) {
            retryCheckTaskWithLog("点击【导入】按钮") {
                wxAccessibilityService.clickById(Nodes.TAGDR_daoruNode.nodeId)
//                wxAccessibilityService.clickByText(Nodes.TAGDR_daoruNode.nodeText)
            }
        }
    }




}