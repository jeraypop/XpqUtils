package com.lygttpod.android.auto.wx.page.send

import android.util.Log
import com.google.android.accessibility.ext.acc.clickById
import com.google.android.accessibility.ext.acc.clickByIdAndText
import com.google.android.accessibility.ext.acc.findById
import com.google.android.accessibility.ext.acc.findNodesById
import com.google.android.accessibility.ext.acc.inputText
import com.google.android.accessibility.ext.acc.isEditText
import com.google.android.accessibility.ext.task.retryCheckTaskWithLog
import com.lygttpod.android.auto.wx.data.NodeInfo
import com.lygttpod.android.auto.wx.page.IPage
import com.lygttpod.android.auto.wx.service.wxAccessibilityService
import com.lygttpod.android.auto.wx.version.nodeProxy

object WXChattingPage : IPage {

    interface Nodes {
        val chattingBottomRootNode: NodeInfo
        val chattingBottomPlusNode: NodeInfo
        val chattingXiangCeNode: NodeInfo
        val chattingSendMsgNode: NodeInfo
        val chattingEditTextNode: NodeInfo

        companion object : Nodes by nodeProxy()
    }

    override fun pageClassName() = "com.tencent.mm.ui.chatting.ChattingUI"

    override fun pageTitleName() = "微信聊天页"

    override fun isMe(): Boolean {
        return wxAccessibilityService?.findById(Nodes.chattingBottomRootNode.nodeId) != null
    }

    suspend fun checkInPage(): Boolean {
        return delayAction(delayMillis = 1000) {
            retryCheckTaskWithLog("检查当前是是否打开聊天页") {
                isMe()
            }
        }
    }

    /**
     * 点击更多功能按钮
     */
    suspend fun clickMoreOption(): Boolean {
        return delayAction(delayMillis = 1000) {
            retryCheckTaskWithLog("点击聊天页的功能区【+】按钮") {
                val clickMoreOption =
                    wxAccessibilityService.clickById(Nodes.chattingBottomPlusNode.nodeId)
                if (!clickMoreOption) {
                    val findSendBtn =
                        wxAccessibilityService?.findById(Nodes.chattingSendMsgNode.nodeId) != null
                    if (findSendBtn) {
                        Log.d("LogTracker", "发现功能区是【发送】按钮，需要先去清空输入框的的内容")
                        val clear = wxAccessibilityService
                            ?.findNodesById(Nodes.chattingEditTextNode.nodeId)
                            ?.lastOrNull { it.isEditText() }
                            ?.inputText("")
                            ?: false
                        if (clear) {
                            Log.d("LogTracker", "已清空输入的草本文本")
                        }
                    }
                }
                clickMoreOption
            }
        }
    }

    /**
     * 是否有 +号按钮
     */
    suspend fun hasPlusBtn(): Boolean {
        return delayAction(delayMillis = 2000) {
            retryCheckTaskWithLog("聊天页是否有【+号】按钮") {
                val findjiahaoBtn =
                    wxAccessibilityService?.findById(Nodes.chattingBottomPlusNode.nodeId) != null
                if (findjiahaoBtn) {
                    Log.d("LogTracker", "+号按钮是存在的")
                }
                findjiahaoBtn
            }
        }
    }



    /**
     * 点击相册
     */
    suspend fun clickPicCe(): Boolean {
        return delayAction(delayMillis = 1000) {
            retryCheckTaskWithLog("点击聊天页功能区的【相册】按钮") {
//                wxAccessibilityService?.printNodeInfo()
//                wxAccessibilityService.findWithClickByText("相册")
                wxAccessibilityService.clickByIdAndText(
                    Nodes.chattingXiangCeNode.nodeId,
                    Nodes.chattingXiangCeNode.nodeText
                )
            }
        }
    }

    /**
     * 粘贴发送内容
     */
    suspend fun inputMsg(msg: String = "你好啊"): Boolean {
        return delayAction(delayMillis = 1000) {
            retryCheckTaskWithLog("在聊天页自动粘贴发送内容") {
                val clickMoreOption =
                    wxAccessibilityService.clickById(Nodes.chattingBottomPlusNode.nodeId)
                if (!clickMoreOption) {
                    val findSendBtn =
                        wxAccessibilityService?.findById(Nodes.chattingSendMsgNode.nodeId) != null
                    if (findSendBtn) {
                        Log.d("LogTracker", "发现功能区是【发送】按钮，需要先去清空输入框的的内容")
                        val clear = wxAccessibilityService
                            ?.findNodesById(Nodes.chattingEditTextNode.nodeId)
                            ?.lastOrNull { it.isEditText() }
                            ?.inputText(msg)
                            ?: false
                        if (clear) {
                            Log.d("LogTracker", "已清空输入的草本文本")
                        }
                    }
                }else{
                    val clear = wxAccessibilityService
                        ?.findNodesById(Nodes.chattingEditTextNode.nodeId)
                        ?.lastOrNull { it.isEditText() }
                        ?.inputText(msg)
                        ?: false

                }
                clickMoreOption
            }
        }
    }


    /**
     * 点击发送按钮
     */
    suspend fun clickSend(): Boolean {
        return delayAction(delayMillis = 1000) {
            retryCheckTaskWithLog("点击【发送】按钮") {
                wxAccessibilityService.clickById(Nodes.chattingSendMsgNode.nodeId)
//                wxAccessibilityService.clickByText(Nodes.chattingSendMsgNode.nodeText)
            }
        }
    }



}