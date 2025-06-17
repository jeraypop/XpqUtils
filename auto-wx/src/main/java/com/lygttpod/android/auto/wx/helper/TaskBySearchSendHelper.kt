package com.lygttpod.android.auto.wx.helper

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.util.Log
import com.google.android.accessibility.ext.acc.pressBackButton
import com.google.android.accessibility.ext.goToWx
import com.google.android.accessibility.ext.toast
import com.google.android.accessibility.ext.window.LogWrapper
import com.google.android.accessibility.ext.window.OverlayLog
import com.lygttpod.android.auto.wx.data.SendUserInfo
import com.lygttpod.android.auto.wx.ktx.formatTime
import com.lygttpod.android.auto.wx.page.qunfa.WX1QunFaZhuShouPage
import com.lygttpod.android.auto.wx.page.qunfa.WX2XuanZePYPage
import com.lygttpod.android.auto.wx.page.qunfa.WX3TagImportPage
import com.lygttpod.android.auto.wx.page.qunfa.WX4SearchTagPage
import com.lygttpod.android.auto.wx.page.qunfa.WX5QunFaPage
import com.lygttpod.android.auto.wx.page.send.WXChattingPage
import com.lygttpod.android.auto.wx.page.send.WXHomePage
import com.lygttpod.android.auto.wx.page.send.WXPicCePage
import com.lygttpod.android.auto.wx.page.send.WXSearchFriendPage
import com.lygttpod.android.auto.wx.service.wxAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 通过搜索指定好友或群来发送内容 任务
 */
object TaskBySearchSendHelper {

    private val taskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    //有序可重复
    private val NotNormalList = mutableListOf<SendUserInfo>()
//    private val NotNormalList = mutableListOf<String>()

    var sender = ""
    var senderContent = ""
    var isPicture = false
    var startIndex = 0
    var countTotal = 1
    val qfzs = "群发助手"
    var isJian1ci = true
    var isTagQunFa = false
    @JvmStatic
    fun sendLog(sendText: String) {
        LogWrapper.logAppend(sendText)
    }
    private val mutex = Mutex()

    fun startTask(context: Context, sendList: List<SendUserInfo>, isContinueLastCheck: Boolean = false, isJian1: Boolean = false,tagQunFa: Boolean = false, start: Long = System.currentTimeMillis()) {
        taskScope.launch {
            if (mutex.isLocked) {
                sendLog("♥♥ 上次任务还没结束哦(有重试机制)，请稍等再试")
                context.toast("上次任务还没结束哦(有重试机制)，请稍等再试")
                return@launch
            }
            mutex.withLock {
                isJian1ci = isJian1
                isTagQunFa = tagQunFa
                sendLog("♥♥ 开始执行【自动发送】任务")
                FriendStatusHelper.taskCallBack?.onTaskStart("开始执行【自动发送】任务")
                val start_wx = System.currentTimeMillis()
                startTagBySearch(context,sendList,isContinueLastCheck)
                val end = System.currentTimeMillis()
                val totalTime = end - start
                val wxTime = end - start_wx
                sendLog("♥♥ 任务在微信中耗时：${wxTime.formatTime()}"+"  数量：" +sendList.size)
                sendLog("♥♥ 任务总耗时：${totalTime.formatTime()}"+"  数量：" +sendList.size)
                FriendStatusHelper.taskCallBack?.onTaskEnd(end - start)

            }
        }
    }

    private suspend fun startTagBySearch(context: Context, sendList: List<SendUserInfo>, isContinueLastCheck: Boolean = false) {
        if (!isContinueLastCheck){
            FriendStatusHelper.lastTagUserCount = 0
            NotNormalList.clear()
//            NotNormalList.add(SendUserInfo("趣安卓","我爱你1",false,0, 1))
//            NotNormalList.add(SendUserInfo("奋斗","我爱你1",false,0, 1))
//            NotNormalList.add(SendUserInfo("秒启动","我爱你1",false,0, 1))
//
//            NotNormalList.add(SendUserInfo("趣安卓","我爱你",true,0, 1))
//            NotNormalList.add(SendUserInfo("奋斗","我爱你",true,0, 1))
//            NotNormalList.add(SendUserInfo("秒启动","我爱你",true,0, 1))
//
//            NotNormalList.add(SendUserInfo("趣安卓","我爱你",true,2, 2))
//            NotNormalList.add(SendUserInfo("奋斗","我爱你",true,3, 3))
//            NotNormalList.add(SendUserInfo("秒启动","我爱你",true,4, 4))

//            NotNormalList.addAll(FriendStatusHelper.filterNotNormalData())
            NotNormalList.addAll(sendList)
        }

//        val Svip = get(CacheConst.SVIP_KEY, false)
//        val vip = get(CacheConst.VIP_KEY, false)
//        if (Svip) {
//            sendLog("♥♥ 您是尊贵的SVIP用户,可使用所有定时器")
//        }else if (vip){
//            sendLog("♥♥ 您是尊贵的VIP用户,可使用定时器①②")
//        }else{
//
//            if (tiyandaoqi){
//                sendLog("♥♥ 您是基础版用户,可免费使用定时器①(第一个任务)")
//            }else{
//                sendLog("♥♥ 您是普通用户,可免费体验所有定时器30分钟")
//            }
//
//        }

        sendLog("♥♥ 本次定时器包含任务数量大小: ${NotNormalList.size}")

        //启动微信
        context.goToWx()
        //判断是否进入到微信
        sendLog("♥♥ 判断是否进入微信")
        val inWxApp = WXHomePage.waitEnterWxApp()
        if (!inWxApp) {
            sendLog("♥♥ 未进入微信,继续等待")
            val inWxApp = WXHomePage.waitEnterWxApp()
            if (!inWxApp) {
                sendLog("♥♥ 未进入微信,终止任务")
                return
            }

        }
        singleTask(context)
        //回到微信首页
        sendLog("♥♥ 任务结束,回到微信首页")
        val isHome = WXHomePage.backToHome()
        if (isHome) {
            //如果是在微信主界面,则调用返回功能
            sendLog("♥♥ 回到微信首页成功,再返回一次")
            wxAccessibilityService?.pressBackButton()
        }else{
            sendLog("♥♥ 回到微信首页失败,尝试再次回退")
            //再次执行 回到微信首页
            val isHome = WXHomePage.backToHome()
            if (isHome) {
                //如果是在微信主界面,则调用返回功能
                sendLog("♥♥ 回到微信首页成功,再返回一次")
                wxAccessibilityService?.pressBackButton()
            }

        }
        CoroutineScope(Dispatchers.Main).launch {
            OverlayLog.show()
        }

        FriendStatusHelper.lastTagUserCount = NotNormalList.size

    }

    @SuppressLint("SuspiciousIndentation")
    private suspend fun singleTask(context: Context) {
        if (NotNormalList.isNullOrEmpty()){
            sendLog("♥♥ 任务列表为空,终止执行")
            return
        }
//        var sender = ""
//        var senderContent = ""
//        var isPicture = false
//        var startIndex = 0
//        var countTotal = 1

        sender = NotNormalList?.first()?.sender.toString()
        senderContent = NotNormalList?.first()?.sendContent.toString()
        isPicture = NotNormalList?.first()?.isPicture?:false
        startIndex = NotNormalList?.first()?.startIndex?:0
        countTotal = NotNormalList?.first()?.countTotal?:1

        //好友(群聊)或标签名为空
        if (TextUtils.isEmpty(sender)||TextUtils.equals("null",sender)){

            if (NotNormalList.size == 1){
                sendLog("♥♥ 任务中的好友(群聊)或标签名为空, 终止执行")
                return
            }else{
                context.toast("♥♥ 任务中的好友(群聊)或标签名为空,继续下一个")
                sendLog("♥♥ 任务中的好友(群聊)或标签名为空,继续下一个")
                removeLoop(context, true)
                return
            }


        }

        //回到微信首页
        //如果不是在微信主界面,则会一直重复返回,直到返回微信主界面
        sendLog("步骤① 执行 回到微信首页")
        val isHome = WXHomePage.backToHome()
        if (!isHome)    {
            sendLog("步骤①.1 未成功回到微信首页,终止任务")
            //如果不是在微信主界面,则先返回微信主界面且停止任务执行
            return
        }

        //微信首页 搜索 图标
        sendLog("步骤② 点击微信首页 搜索 图标")
        WXSearchFriendPage.clickRightTopSearchBtn()
        //判断当前是否在 搜索好友 页
        sendLog("步骤③ 判断当前是否在 【搜索好友】 页面")
        val isCreateGroupPage = WXSearchFriendPage.checkInPage()
        if (!isCreateGroupPage)    {
            sendLog("步骤③.1 未进入【搜索好友】页面,尝试重新点击微信首页 搜索 图标")
            //再次点击 微信首页 搜索 图标
            WXSearchFriendPage.clickRightTopSearchBtn()
            //再次判断 当前是否在 搜索好友 页
            sendLog("步骤③.2 再次判断当前是否在 【搜索好友】 页面")
            val isCreateGroupPage = WXSearchFriendPage.checkInPage()
            if (!isCreateGroupPage)    {
                sendLog("步骤③.3 未进入【搜索好友】页面,终止任务")
                return
            }
        }



        if (isTagQunFa){
            //群发任务
            if (qunfaTask()) return


        }
        else{
            //单发任务
            if (danfaTask(context)) return
        }

        //一个任务完整执行,移除当前任务,并执行下一个任务
        removeLoop(context,true)
        //==================================



    }

    private suspend fun danfaTask(context: Context): Boolean {
        //粘贴好友名字
        sendLog("步骤④ 粘贴好友名字到搜索框")
        WXSearchFriendPage.pasteFriend(sender)
        //点击 该好友
        sendLog("步骤⑤ 点击搜索结果中的 该好友")
        WXSearchFriendPage.clickFriendByIdAndText(sender)
        //检查当前是否进入到聊天页
        sendLog("步骤⑥ 判断是否进入到聊天页")
        // （测试中发现有时候点击有效，但是没有进入到聊天页，所以在重试一次）
        val check = WXChattingPage.checkInPage()
        if (!check) {
            //再次点击该好友
            sendLog("⑥.1 未进入聊天页,再次点击该好友")
            WXSearchFriendPage.clickFriendByIdAndText(sender)
            //再次检查是否进入到聊天页
            sendLog("⑥.2 再次判断是否进入到聊天页")
            val checkin = WXChattingPage.checkInPage()
            if (!checkin) {
                //继续下一个好友
                sendLog("⑥.3 未进入聊天页,中止本次")
                removeLoop(context, true)
                return true
            }

        }

        //点击聊天页功能区按钮 +号
        sendLog("步骤⑦ 点击聊天页功能区按钮 +号")
        WXChattingPage.clickMoreOption()
        if (!isPicture) {
            //粘贴发送内容
            sendLog("步骤⑧ 粘贴要发送的文字")
            WXChattingPage.inputMsg(senderContent)
            //点击发送按钮
            sendLog("步骤⑨ 点击发送按钮")
            //在vivo机型上,就算点击成功了,返回值也可能是false
            //所以去掉判断 但是 因为返回值是false,所以会一直循环重试 耽误时间
            WXChattingPage.clickSend()
            //是否还有+号按钮
            sendLog("步骤⑩ 检查是否发送成功")
            val has = WXChattingPage.hasPlusBtn()
            if (!has) {
                sendLog("步骤⑩.1 点击发送按钮失败,尝试重新点击")
                WXChattingPage.clickSend()
                //再次判断 是否还有+号按钮
                sendLog("步骤⑩.2 再次检查是否发送成功")
                val has = WXChattingPage.hasPlusBtn()
                if (!has) {
                    //发送失败
                    sendLog("步骤⑩.3 点击发送按钮失败,中止本次")
                } else {
                    //发送成功
                    sendLog("步骤⑩.3 发送成功")
                }


            } else {
                //发送成功
                sendLog("步骤⑩.1 发送成功")

            }

        } else {
            //点击聊天页功能区相册按钮
            sendLog("步骤⑧ 点击聊天页功能区相册按钮")
            WXChattingPage.clickPicCe()
            //是否在相册页
            sendLog("步骤⑨ 检查是否在相册页")
            val isInPage = WXPicCePage.checkInPage()
            if (!isInPage) {
                sendLog("步骤⑨.1 未进入相册页,尝试重新点击聊天页功能区相册按钮")
                //再次点击聊天页功能区相册按钮
                WXChattingPage.clickPicCe()
                sendLog("步骤⑨.2 再次检查是否在相册页")
                //再次判断是否在相册页
                val isInPage = WXPicCePage.checkInPage()
                if (!isInPage) {
                    sendLog("步骤⑨.3 未进入相册页,终止任务")
                    return true
                }

            }
            //选中一些图片视频
            sendLog("步骤⑩ 选中相册中一些图片或视频")
            WXPicCePage.selectSomPic(startIndex, countTotal)
            //点击原图按钮
            sendLog("步骤⑪ 勾选原图按钮")
            WXPicCePage.clickYuanTu()
            //点击发送按钮
            sendLog("步骤⑫ 点击发送按钮")
            WXPicCePage.clickSend()


            //检查当前是否从相册界面进入到聊天页
            sendLog("步骤⑬ 检查是否发送成功")
            val check = WXChattingPage.checkInPage()
            if (!check) {
                sendLog("步骤⑬.1 点击发送按钮失败,尝试重新点击")
                WXPicCePage.clickSend()
                //再次检查当前是否从相册界面进入到聊天页
                sendLog("步骤⑬.2 再次检查是否发送成功")
                val check = WXChattingPage.checkInPage()
                if (!check) {
                    //发送失败
                    sendLog("步骤⑬.3 点击发送按钮失败,中止本次")

                } else {
                    //发送成功
                    sendLog("步骤⑬.3 发送成功")
                }

            } else {
                //发送成功
                sendLog("步骤⑬.1 发送成功")
            }

        }
        return false
    }

    private suspend fun qunfaTask(): Boolean {
        //粘贴好友名字 (群发助手)
        sendLog("步骤④ 粘贴好友名字(群发助手)到搜索框")
        WXSearchFriendPage.pasteFriend(qfzs)
        //点击 该好友  群发助手
        sendLog("步骤⑤ 点击搜索结果中的 该好友 (群发助手)")
        WXSearchFriendPage.clickFriendByIdAndText(qfzs)
        //检查当前是否进入到群发助手页
        sendLog("步骤⑥ 判断是否进入到 群发助手页")
        // （测试中发现有时候点击有效，但是没有进入，所以在重试一次）
        val check = WX1QunFaZhuShouPage.checkInPage()
        if (!check) {
            //再次点击该好友  群发助手
            sendLog("⑥.1 未进入群发助手页,再次点击 群发助手")
            WXSearchFriendPage.clickFriendByIdAndText(qfzs)
            //再次检查是否进入到群发助手页
            sendLog("⑥.2 再次判断是否进入到 群发助手页")
            val checkin = WX1QunFaZhuShouPage.checkInPage()
            if (!checkin) {
                //终止执行
                sendLog("⑥.3 未进入群发助手页,终止执行")
                return true
            }

        }
        //在群发助手页 点击 新建群发 按钮
        sendLog("步骤⑦ 在群发助手页 点击 新建群发 按钮")
        WX1QunFaZhuShouPage.clickNewQunFa()
        //检查当前是否进入到选择朋友页
        sendLog("步骤⑧ 判断是否进入到 选择朋友页")
        val check2 = WX2XuanZePYPage.checkInPage()
        if (!check2) {
            //再次点击 新建群发
            sendLog("步骤⑧.1 未进入选择朋友页,再次点击 新建群发")
            WX1QunFaZhuShouPage.clickNewQunFa()
            //再次检查当前是否进入到选择朋友页
            sendLog("步骤⑧.2 再次判断是否进入到选择朋友页")
            val check2 = WX2XuanZePYPage.checkInPage()
            if (!check2) {
                sendLog("步骤⑧.3 未进入到 选择朋友页,终止执行")
                return true
            }

        }
        //在选择朋友页 点击  从标签导入 按钮
        sendLog("步骤⑨ 在选择朋友页 点击  从标签导入 按钮")
        WX2XuanZePYPage.clickTagimport()
        //判断 是否进入 从标签导入 页
        sendLog("步骤⑩ 判断是否进入到 从标签导入 页")
        val inPage = WX3TagImportPage.checkInPage()
        if (!inPage) {
            //再次 在选择朋友页 点击  从标签导入 按钮
            sendLog("步骤⑩.1 再次 在选择朋友页 点击  从标签导入 按钮")
            WX2XuanZePYPage.clickTagimport()
            //再次判断 是否进入 从标签导入 页
            sendLog("步骤⑩.2 再次判断 是否进入到 从标签导入 页")
            val inPage = WX3TagImportPage.checkInPage()
            if (!inPage) {
                sendLog("步骤⑩.3 未进入到 从标签导入 页,终止执行")
                return true
            }
        }
        //在 从标签导入页 点击 搜索 按钮
        sendLog("步骤⑪ 在 从标签导入页 点击  搜索 按钮")
        WX3TagImportPage.clickSearch()
        //判断 是否进入 搜索标签页
        sendLog("步骤⑫ 判断 是否进入到 搜索标签页 页")
        val inPage1 = WX4SearchTagPage.checkInPage()
        if (!inPage1) {
            //再次 在 从标签导入页 点击 搜索 按钮
            sendLog("步骤⑫.1 再次 在 从标签导入页 点击 搜索 按钮")
            WX3TagImportPage.clickSearch()
            //再次判断 是否进入 搜索标签页
            sendLog("步骤⑫.2 再次判断 是否进入到 搜索标签页 页")
            val inPage1 = WX4SearchTagPage.checkInPage()
            if (!inPage1) {
                sendLog("步骤⑫.3 未进入 搜索标签页 页,终止执行")
                return true
            }
        }
        //在搜索标签页 粘贴标签名字
        sendLog("步骤⑬ 在搜索标签页 粘贴标签名字")
        WX4SearchTagPage.pasteTag(sender)
        //点击 搜索出来的标签
        sendLog("步骤⑭ 点击 搜索出来的标签")
        WX4SearchTagPage.clickTagByIdAndText(sender)
        //判断 是否进入 从标签导入 页
        sendLog("步骤⑮ 判断 是否进入 从标签导入 页")
        val inPage2 = WX3TagImportPage.checkInPage()
        if (!inPage2) {
            //再次 点击 搜索出来的标签
            sendLog("步骤⑮.1 再次 点击 搜索出来的标签")
            WX4SearchTagPage.clickTagByIdAndText(sender)
            //再次判断 是否进入 从标签导入 页
            sendLog("步骤⑮.2 再次判断 是否进入到 从标签导入 页")
            val inPage2 = WX3TagImportPage.checkInPage()
            if (!inPage2) {
                sendLog("步骤⑮.3 未进入 从标签导入 页,终止执行")
                return true
            }
        }
        //在 从标签导入 页 点击 导入 按钮
        sendLog("步骤⑯ 在 从标签导入 页 点击 导入 按钮")
        WX3TagImportPage.clickDaoRu()
        //判断 是否进入 选择朋友页
        sendLog("步骤⑰ 判断 是否进入 选择朋友页")
        val inPage3 = WX2XuanZePYPage.checkInPage()
        if (!inPage3) {
            //再次 在 从标签导入 页 点击 导入 按钮
            sendLog("步骤⑰.1 再次 在 从标签导入 页 点击 导入 按钮")
            WX3TagImportPage.clickDaoRu()
            //再次判断 是否进入 选择朋友页
            sendLog("步骤⑰.2 再次判断 是否进入 选择朋友页")
            val inPage3 = WX2XuanZePYPage.checkInPage()
            if (!inPage3) {
                sendLog("步骤⑰.3 未进入 选择朋友页,终止执行")
                return true
            }
        }


        //在选择朋友页 点击 选中 按钮
        sendLog("步骤⑱ 在选择朋友页 点击 选中 按钮")
        WX2XuanZePYPage.clickSelect()
        //判断 是否进入 群发页
        sendLog("步骤⑲ 判断 是否进入 群发页")
        val inPage4 = WX5QunFaPage.checkInPage()
        if (!inPage4) {
            //再次 在选择朋友页 点击 选中 按钮
            sendLog("步骤⑲.1 再次 在选择朋友页 点击 选中 按钮")
            WX2XuanZePYPage.clickSelect()
            //再次判断 是否进入 群发页
            sendLog("步骤⑲.2 再次判断 是否进入 群发页")
            val inPage4 = WX5QunFaPage.checkInPage()
            if (!inPage4) {
                sendLog("步骤⑲.3 未进入 群发页,终止执行")
                return true
            }
        }

        //点击群发页功能区按钮 +号
        sendLog("步骤⑳ 点击群发页功能区按钮 +号")
        WX5QunFaPage.clickMoreOption()
        if (!isPicture) {
            //粘贴发送内容
            sendLog("步骤㉑ 粘贴要发送的文字")
            WX5QunFaPage.inputMsg(senderContent)
            //点击发送按钮
            sendLog("步骤㉒ 点击发送按钮")
            //在vivo机型上,就算点击成功了,返回值也可能是false
            //所以去掉判断 但是 因为返回值是false,所以会一直循环重试 耽误时间
            WX5QunFaPage.clickSend()
            //点击第二个发送按钮
            sendLog("步骤㉓ 点击第二个发送按钮")
            WX5QunFaPage.clickSend2()
            // 判断是否在 群发助手 页
            sendLog("步骤㉔ 判断是否在 群发助手 页(发送是否成功)")
            val inPage5 = WX1QunFaZhuShouPage.checkInPage()
            if (!inPage5) {
                //再次点击第二个发送按钮
                sendLog("步骤㉔.1 尝试重新点击第二个发送按钮")
                WX5QunFaPage.clickSend2()
                //再次判断是否在 群发助手 页
                sendLog("步骤㉔.2 尝试重新判断是否在 群发助手 页(发送是否成功)")
                val inPage5 = WX1QunFaZhuShouPage.checkInPage()
                if (!inPage5) {
                    sendLog("步骤㉔.3 未进入 群发助手 页(群发失败),终止执行")
                    return true
                } else {
                    //发送成功
                    sendLog("步骤㉔.3 群发成功")
                }
            } else {
                //发送成功
                sendLog("步骤㉔.1 群发成功")

            }

        } else {
            //点击群发页功能区相册按钮
            sendLog("步骤㉑ 点击群发页功能区相册按钮")
            WX5QunFaPage.clickPicCe()
            //是否在相册页
            sendLog("步骤㉒ 检查是否在相册页")
            val isInPage = WXPicCePage.checkInPage()
            if (!isInPage) {
                sendLog("步骤㉒.1 未进入相册页,尝试重新点击群发页功能区相册按钮")
                //再次点击聊天页功能区相册按钮
                WX5QunFaPage.clickPicCe()
                sendLog("步骤㉒.2 再次检查是否在相册页")
                //再次判断是否在相册页
                val isInPage = WXPicCePage.checkInPage()
                if (!isInPage) {
                    sendLog("步骤㉒.3 未进入相册页,终止任务")
                    return true
                }

            }
            //选中一些图片视频
            sendLog("步骤㉓ 选中相册中一些图片或视频")
            WXPicCePage.selectSomPic(startIndex, countTotal)
            //点击原图按钮
            sendLog("步骤㉔ 勾选原图按钮")
            WXPicCePage.clickYuanTu()
            //点击发送按钮
            sendLog("步骤㉕ 点击发送按钮")
            WXPicCePage.clickSend()
            //点击第二个发送按钮
            sendLog("步骤㉖ 点击第二个发送按钮")
            WX5QunFaPage.clickSend2()
            // 判断是否在 群发助手 页
            sendLog("步骤㉗ 判断是否在 群发助手 页(发送是否成功)")
            val inPage5 = WX1QunFaZhuShouPage.checkInPage()
            if (!inPage5) {
                //再次点击第二个发送按钮
                sendLog("步骤㉗.1 尝试重新点击第二个发送按钮")
                WX5QunFaPage.clickSend2()
                //再次判断是否在 群发助手 页
                sendLog("步骤㉗.2 尝试重新判断是否在 群发助手 页(发送是否成功)")
                val inPage5 = WX1QunFaZhuShouPage.checkInPage()
                if (!inPage5) {
                    sendLog("步骤㉗.3 未进入 群发助手 页(群发失败),终止执行")
                    return true
                } else {
                    //发送成功
                    sendLog("步骤㉗.3 群发成功")
                }
            } else {
                //发送成功
                sendLog("步骤㉗.1 群发成功")

            }


        }
        return false
    }

    private suspend fun removeLoop(context: Context,isLoop: Boolean = false) {
        if (!isLoop)return
//        NotNormalList?.removeFirst()
        //    >= 1
        if (NotNormalList.isNotEmpty()) {
            if (NotNormalList.size >= 2){
                sendLog("♥♥ 继续下一个任务")

            }else if (NotNormalList.size == 1){
                sendLog("♥♥ 没有下一个任务了")
                //===任务个数为1的时候,次数才－1 避免重复减次数========
//                if (isJian1ci){
//                    val Svip = get(CacheConst.SVIP_KEY, false)
//                    val vip = get(CacheConst.VIP_KEY, false)
//                    if (!Svip&&!vip&& tiyandaoqi) {
//                        //激励-1
//                        put(CacheConst.JILIADCISHU, get(CacheConst.JILIADCISHU, 0) - 1)
//                    }
//                }

            }
            //移除第一个任务  集合大小减 1
            NotNormalList.removeAt(0)
        }
        //移除集合第一个元素后,再次判断集合大小是否为0
        if (NotNormalList.isNotEmpty()) {
            delay(2000)
            //循环执行以上步骤
            singleTask(context)
        }



    }
}