package com.lygttpod.android.auto.wx.page.send

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


object WXSearchFriendPage : IPage {

    interface Nodes {

        val homeRightTopSearchNode: NodeInfo  //h5n  主界面右上角搜索图标
        val searchVoiceNode: NodeInfo  //nhn  搜索界面语音搜索图标
        val searchEditTextNode: NodeInfo  //d98  搜索界面搜索输入框
        val searchFriendNode: NodeInfo  //  odf  搜索界面找到的好友昵称            聊天记录kbq

        companion object : Nodes by nodeProxy()
    }

    override fun pageClassName() = "com.tencent.mm.plugin.fts.ui.FTSMainUI"

    override fun pageTitleName() = "微信搜索好友页"

    override fun isMe(): Boolean {
        //微信搜索好友页
        return wxAccessibilityService?.findById(Nodes.searchVoiceNode.nodeId) != null
    }

    suspend fun checkInPage(): Boolean {
        return delayAction {
            retryCheckTaskWithLog("检查当前是否打开微信搜索好友页") {
                isMe()
            }
        }
    }

    /**
     * 粘贴 好友
     */
    suspend fun pasteFriend(friendtxt: String): Boolean {
        return delayAction(delayMillis = 1000) {
            retryCheckTaskWithLog("粘贴【搜索区】输入框") {

                val tagEdit = wxAccessibilityService
                    ?.findNodesById(Nodes.searchEditTextNode.nodeId)
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
     * 点击 好友
     */
    suspend fun clickFriend(): Boolean {
        return delayAction(delayMillis = 1000) {
            retryCheckTaskWithLog("点击【搜索区】呈现的第一个好友") {
                wxAccessibilityService.clickById(
                    Nodes.searchFriendNode.nodeId
                )

            }
        }
    }

    suspend fun clickFriendByIdAndText(friendtxt: String): Boolean {
        return delayAction(delayMillis = 1000) {
            retryCheckTaskWithLog("点击【搜索区】匹配特定名称的好友") {
                wxAccessibilityService.clickByIdAndTextFilter(
                    Nodes.searchFriendNode.nodeId,
                    friendtxt
                )
            }
        }
    }



    /**
     * 点击微信首页右上角的 搜索 按钮
     */
    suspend fun clickRightTopSearchBtn(): Boolean {
        return delayAction(delayMillis = 1000) {
            retryCheckTaskWithLog("点击首页右上角的【搜索】按钮") {
                //android.widget.RelativeLayout → text =  → id = com.tencent.mm:id/grs → description = 更多功能按钮
                wxAccessibilityService.clickById(Nodes.homeRightTopSearchNode.nodeId)
//                wxAccessibilityService?.findById("com.tencent.mm:id/grs")?.click() == true
            }
        }
    }

    /**
     *
     * @Title : filter
     * @Type : FilterStr
     * @date : 2014年3月12日 下午9:17:22
     * @Description : 过滤出字母、数字和中文 character = character.replace(regEx.toRegex(), "")
     *
     * character = character.replace(regEx.toRegex(), "%&") 非字母、数字和中文全部替换为%&
     * @param character
     * @return
     */
    suspend fun filter(character: String): String {
        var character = character
//        val regEx = "[^a-zA-Z0-9\\u4e00-\\u9fa5\\x20-\\x7E]"
        val regEx = "[^a-zA-Z0-9\\u4e00-\\u9fa5]"
        character = character.replace(regEx.toRegex(), "%&")
        return character
    }

    suspend fun startMatch(input: String): Boolean {
        // 正则表达式，"^" 符号表示字符串的开始
        //中文,英文,数字 Unicode
        var regex: String = "^[a-zA-Z0-9\\u4e00-\\u9fa5\\x20-\\x7E]"
        // 创建Pattern对象
        var pattern: Pattern = Pattern.compile(regex)

        // 创建Matcher对象
        var matcher: Matcher = pattern.matcher(input)

        // 检查是否匹配
        var isMatch: Boolean = matcher.lookingAt()
        Log.e("异常列表", "startMatch: "+isMatch )
        return isMatch
    }
    suspend fun startMatch_CN(input: String): Boolean {
        // 正则表达式，"^" 符号表示字符串的开始
        //中文,英文,数字
        var regex: String = "^[a-zA-Z0-9\\u4e00-\\u9fa5]"
        // 创建Pattern对象
        var pattern: Pattern = Pattern.compile(regex)

        // 创建Matcher对象
        var matcher: Matcher = pattern.matcher(input)

        // 检查是否匹配
        var isMatch: Boolean = matcher.lookingAt()
        Log.e("异常列表", "startMatch: "+isMatch )
        return isMatch
    }

    /*
    *
    * 表示整个字符串必须由一个或多个中文字符、英文字母或数字组成。
    * */
    suspend fun Match_CN(input: String): Boolean {
        // 正则表达式，匹配整个字符串由中文、英文或数字组成
        val regex = "^[a-zA-Z0-9\\u4e00-\\u9fa5]+$"

        // 直接使用字符串的 matches() 函数
        return input.matches(Regex(regex))
    }


    suspend fun endMatch(input: String): Boolean {
        // 定义正则表达式，注意结尾处的$符号表示字符串的结尾
        val regex = "[a-zA-Z0-9\\u4e00-\\u9fa5\\x20-\\x7E]$"
        // 创建Pattern对象
        val pattern = Pattern.compile(regex)
        // 创建Matcher对象
        val matcher = pattern.matcher(input)
        // 检查是否匹配
        val isMatch = matcher.find()
        Log.e("异常列表", "endMatch: "+isMatch )
        return isMatch
    }

    suspend fun anyMatch(input: String): Boolean {

        // 正则表达式，匹配大小写字母、数字、中文字符或ASCII可打印字符
        val regex = "[a-zA-Z0-9\\u4e00-\\u9fa5\\x20-\\x7E]"
        // 创建Pattern对象
        val pattern = Pattern.compile(regex)
        // 创建Matcher对象
        val matcher = pattern.matcher(input)
        // 检查是否在字符串的任何位置找到匹配项
        val isMatch = matcher.find()
        Log.e("异常列表", "anyMatch: "+isMatch )
        return isMatch
    }



}