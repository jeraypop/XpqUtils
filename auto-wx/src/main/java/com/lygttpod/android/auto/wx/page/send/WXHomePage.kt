package com.lygttpod.android.auto.wx.page.send

import android.util.Log
import com.google.android.accessibility.ext.acc.*
import com.google.android.accessibility.ext.task.TIMEOUT
import com.google.android.accessibility.ext.task.retryCheckTaskWithLog
import com.lygttpod.android.auto.wx.data.NodeInfo
import com.lygttpod.android.auto.wx.page.IPage
import com.lygttpod.android.auto.wx.service.WXAccessibility
import com.lygttpod.android.auto.wx.service.wxAccessibilityService
import com.lygttpod.android.auto.wx.version.nodeProxy
import kotlinx.coroutines.delay

object WXHomePage : IPage {

    interface Nodes {
        val homeBottomNavNode: NodeInfo
        val bottomNavContactsTabNode: NodeInfo
        val bottomNavMineTabNode: NodeInfo
        val homeRightTopPlusNode: NodeInfo
        val createGroupNode: NodeInfo

        companion object : Nodes by nodeProxy()
    }

    override fun pageClassName() = "com.tencent.mm.ui.LauncherUI"

    override fun pageTitleName() = "微信首页"

    /**
     * com.tencent.mm:id/huj是微信首页底导布局id 微信 通讯录 朋友圈  我
     * 找到这个节点就可以说明当前在微信首页
     * 但具体是哪个tab页，无法确定哦
     */
    override fun isMe(): Boolean {
        return wxAccessibilityService?.findById(Nodes.homeBottomNavNode.nodeId) != null
    }

    /**
     * 等待打开微信APP
     * 判断是否已在微信中
     */
    suspend fun waitEnterWxApp(): Boolean {
        return delayAction {
            retryCheckTaskWithLog("等待打开微信APP",TIMEOUT*2) {
                val inApp = isMe()
                WXAccessibility.isInWXApp.set(inApp)
                inApp
            }
        }
    }

    /**
     * 1.如果本身已经在微信主界面,则直接返回  且不再重试机制
     * 2.如果不在微信主界面,先判断是否在微信中,如果在则触发返回功能
     * 如果不在,则抛出异常
     * 因为函数有重试机制,故,如果不在微信主界面,则会一直触发返回功能
     */
    suspend fun backToHome(): Boolean {
        return retryCheckTaskWithLog("打开[微信首页]") {
            if (isMe()) {
                true
            } else {
                // TODO: 判断不准确，待完善
                //在微信里面,但是不在主界面,则调用返回功能
                if (WXAccessibility.isInWXApp.get()) {
                    wxAccessibilityService?.pressBackButton()
                } else {
                    throw RuntimeException("检测到不再微信首页了，终止自动化程序")
                }
                false
            }
        }
    }

    /**
     * 点击通讯录tab
     */
    suspend fun clickContactsTab(doubleClick: Boolean = false): Boolean {
        return delayAction {
            retryCheckTaskWithLog("点击【通讯录】tab") {
                val tabNode = wxAccessibilityService?.findByIdAndText(Nodes.bottomNavContactsTabNode.nodeId, Nodes.bottomNavContactsTabNode.nodeText)
                if (doubleClick) {
                    Log.d("clickContactsTab", "双击 通讯录 tab 第一次")
                    tabNode.click()
                    delay(300)
                    Log.d("clickContactsTab", "双击 通讯录 tab 第二次")
                    tabNode.click()
                } else {
                    if (tabNode?.isSelected == true) {
                        Log.d("clickContactsTab", "已经在 通讯录 页面了  无需再点击")
                        true
                    } else {
                        Log.d("clickContactsTab", "单击 通讯录 tab")
                        tabNode.click()
                    }
                }
            }
        }
    }

    /**
     * 点击 我 tab
     */
    suspend fun clickMineTab(): Boolean {
        return delayAction {
            retryCheckTaskWithLog("点击【我的】tab") {
                wxAccessibilityService?.findByIdAndText(
                    Nodes.bottomNavMineTabNode.nodeId,
                    Nodes.bottomNavMineTabNode.nodeText
                ).click()
            }
        }
    }





}