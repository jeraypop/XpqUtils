package com.lygttpod.android.auto.wx.page.send

import com.google.android.accessibility.ext.acc.clickById
import com.google.android.accessibility.ext.acc.clickByIdAndDesc
import com.google.android.accessibility.ext.acc.clickMultipleByIdAndText
import com.google.android.accessibility.ext.acc.findById
import com.google.android.accessibility.ext.task.retryCheckTaskWithLog
import com.lygttpod.android.auto.wx.data.NodeInfo
import com.lygttpod.android.auto.wx.page.IPage

import com.lygttpod.android.auto.wx.service.wxAccessibilityService
import com.lygttpod.android.auto.wx.version.nodeProxy

object WXPicCePage : IPage {

    interface Nodes {
//        val xiangceRecyclerViewNode: NodeInfo //com.tencent.mm:id/jdw
        val xiangceSendNode: NodeInfo //com.tencent.mm:id/kaq
        val xiangceCheckBoxNode: NodeInfo //com.tencent.mm:id/jdh
        val xiangceImageButtonNode: NodeInfo //com.tencent.mm:id/km5  未选中原图复选框


        companion object : Nodes by nodeProxy()
    }

    override fun pageClassName() = "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI"

    override fun pageTitleName() = "发送图片时,相册页"

    override fun isMe(): Boolean {
        return wxAccessibilityService?.findById(Nodes.xiangceSendNode.nodeId) != null
    }

    suspend fun checkInPage(): Boolean {
        return delayAction {
            retryCheckTaskWithLog("检查当前是否打开相册页") {
                isMe()
            }
        }
    }



    /**
     * 选中一些图片
     */
    suspend fun selectSomPic(startIndex: Int = 0,count: Int = 1): Boolean {
        return delayAction(delayMillis = 1000) {
            retryCheckTaskWithLog("选中相册里的一些图片") {
//                wxAccessibilityService?.printNodeInfo()
//                wxAccessibilityService.findWithClickByText("相册")
                wxAccessibilityService.clickMultipleByIdAndText(
                    Nodes.xiangceCheckBoxNode.nodeId,
                    Nodes.xiangceCheckBoxNode.nodeText,
                    startIndex,
                    count
                )
            }
        }
    }

    /**
     * 点击【发送()】按钮
     */
    suspend fun clickSend(): Boolean {
        return delayAction(delayMillis = 1000) {
            retryCheckTaskWithLog("点击【发送()】按钮") {
                wxAccessibilityService.clickById(Nodes.xiangceSendNode.nodeId)
//                wxAccessibilityService.clickByText(Nodes.xiangceSendNode.nodeText)
            }
        }
    }

    /**
     * 点击【原图】按钮
     */
    suspend fun clickYuanTu(): Boolean {
        return delayAction(delayMillis = 1000) {
            retryCheckTaskWithLog("点击【原图】按钮") {
                wxAccessibilityService.clickByIdAndDesc(
                    Nodes.xiangceImageButtonNode.nodeId,
                    Nodes.xiangceImageButtonNode.nodeText)
            }
        }
    }


}