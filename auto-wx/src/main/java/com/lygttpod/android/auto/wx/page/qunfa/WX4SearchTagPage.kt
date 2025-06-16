package com.lygttpod.android.auto.wx.page.qunfa

import android.util.Log
import com.google.android.accessibility.ext.acc.clickById
import com.google.android.accessibility.ext.acc.clickByIdAndTextFilter
import com.google.android.accessibility.ext.acc.findById
import com.google.android.accessibility.ext.acc.findNodesById
import com.google.android.accessibility.ext.acc.inputText
import com.google.android.accessibility.ext.acc.isEditText
import com.google.android.accessibility.ext.task.retryCheckTaskWithLog
import com.lygttpod.android.auto.wx.data.NodeInfo
import com.lygttpod.android.auto.wx.page.IPage
import com.lygttpod.android.auto.wx.service.wxAccessibilityService
import com.lygttpod.android.auto.wx.version.nodeProxy
import java.util.regex.Matcher
import java.util.regex.Pattern


object WX4SearchTagPage : IPage {

    interface Nodes {


        val searchTagEditTextNode: NodeInfo  //d98  标签搜索界面搜索输入框
        val searchTagNode: NodeInfo  //  hs8  标签搜索界面找到的标签

        companion object : Nodes by nodeProxy()
    }

    override fun pageClassName() = "com.tencent.mm.plugin.label.ui.searchLabel.LabelSearchUI"

    override fun pageTitleName() = "微信搜索标签页"

    override fun isMe(): Boolean {
        return wxAccessibilityService?.findById(Nodes.searchTagEditTextNode.nodeId) != null
    }

    suspend fun checkInPage(): Boolean {
        return delayAction {
            retryCheckTaskWithLog("检查当前是否打开微信搜索标签页") {
                isMe()
            }
        }
    }

    /**
     * 粘贴 标签
     */
        suspend fun pasteTag(friendtxt: String): Boolean {
        return delayAction(delayMillis = 1000) {
            retryCheckTaskWithLog("粘贴【搜索区】输入框") {

                val tagEdit = wxAccessibilityService
                    ?.findNodesById(Nodes.searchTagEditTextNode.nodeId)
                    ?.firstOrNull{ it.isEditText() }
                    ?.inputText(friendtxt)
                    ?: false
                if (tagEdit) {

                    Log.d("LogTracker", "已粘贴:= "+friendtxt)
                }
                tagEdit
            }
        }
    }

    /**
     * 点击 标签
     */
    suspend fun clickTagByIdAndText(friendtxt: String): Boolean {
        return delayAction(delayMillis = 1000) {
            retryCheckTaskWithLog("点击【搜索区】匹配特定名称的好友") {
                wxAccessibilityService.clickByIdAndTextFilter(
                    Nodes.searchTagNode.nodeId,
                    friendtxt
                )
            }
        }
    }





}