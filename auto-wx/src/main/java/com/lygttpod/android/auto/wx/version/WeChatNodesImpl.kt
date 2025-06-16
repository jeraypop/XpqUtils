package com.lygttpod.android.auto.wx.version

import com.lygttpod.android.auto.wx.data.NodeInfo
import com.lygttpod.android.auto.wx.page.qunfa.WX1QunFaZhuShouPage
import com.lygttpod.android.auto.wx.page.qunfa.WX3TagImportPage
import com.lygttpod.android.auto.wx.page.qunfa.WX2XuanZePYPage
import com.lygttpod.android.auto.wx.page.qunfa.WX4SearchTagPage
import com.lygttpod.android.auto.wx.page.qunfa.WX5QunFaPage
import com.lygttpod.android.auto.wx.page.send.WXChattingPage
import com.lygttpod.android.auto.wx.page.send.WXHomePage
import com.lygttpod.android.auto.wx.page.send.WXPicCePage
import com.lygttpod.android.auto.wx.page.send.WXSearchFriendPage

@Suppress("EnumEntryName")
enum class WeChatNodesImpl(val version: String) :
    WX5QunFaPage.Nodes,
    WX4SearchTagPage.Nodes,
    WX3TagImportPage.Nodes,
    WX2XuanZePYPage.Nodes,
    WX1QunFaZhuShouPage.Nodes,
    WXPicCePage.Nodes,
    WXSearchFriendPage.Nodes,
    WXHomePage.Nodes,
    WXChattingPage.Nodes
    {
    WechatVersion_8_0_58("8.0.58") {
        //搜索朋友页
        override val homeRightTopSearchNode =
            NodeInfo("", "com.tencent.mm:id/jha", "首页右上角【搜索】按钮")
        override val searchVoiceNode =
            NodeInfo("", "com.tencent.mm:id/nhn", "搜索界面【语音搜索】图标")
        override val searchEditTextNode =
            NodeInfo("", "com.tencent.mm:id/d98", "搜索界面【搜索】输入框")
        override val searchFriendNode =
            NodeInfo("", "com.tencent.mm:id/odf", "搜索界面【好友昵称】被找到的")

        //首页
        override val homeBottomNavNode = NodeInfo("", "com.tencent.mm:id/huj", "首页底导布局")
        override val bottomNavContactsTabNode =
            NodeInfo("通讯录", "com.tencent.mm:id/icon_tv", "首页底导【通讯录】tab")
        override val bottomNavMineTabNode =
            NodeInfo("我", "com.tencent.mm:id/icon_tv", "首页底导【我】tab")
        override val homeRightTopPlusNode =
            NodeInfo("", "com.tencent.mm:id/jga", "首页右上角【加号】按钮")
        override val createGroupNode = NodeInfo(
            "发起群聊",
            "com.tencent.mm:id/obc",
            "点击首页右上角【加号】按钮后弹框中的【发起群聊】按钮"
        )

        //聊天页功能区
        override val chattingBottomRootNode = NodeInfo("", "com.tencent.mm:id/k52", "聊天页底部功能区的跟节点FrameLayout")
        override val chattingBottomPlusNode = NodeInfo("", "com.tencent.mm:id/bjz", "聊天页底部的【+】按钮")
        override val chattingXiangCeNode = NodeInfo("相册", "com.tencent.mm:id/a12", "聊天页底部功能区的【相册】按钮")
        override val chattingSendMsgNode = NodeInfo("", "com.tencent.mm:id/bql", "聊天页底部功能区的【发送】按钮")
        override val chattingEditTextNode = NodeInfo("", "com.tencent.mm:id/bkk", "聊天页底部功能区的【输入框】EditText")
        //群发页功能区  和聊天页功能区 一样
        override val qunfaBottomRootNode = NodeInfo("", "com.tencent.mm:id/k52", "群发页底部功能区的跟节点FrameLayout")
        override val qunfaBottomPlusNode = NodeInfo("", "com.tencent.mm:id/bjz", "群发页底部的【+】按钮")
        override val qunfaXiangCeNode = NodeInfo("相册", "com.tencent.mm:id/a12", "群发页底部功能区的【相册】按钮")
        override val qunfaSendMsgNode = NodeInfo("", "com.tencent.mm:id/bql", "群发页底部功能区的【发送】按钮")
        override val qunfaEditTextNode = NodeInfo("", "com.tencent.mm:id/bkk", "群发页底部功能区的【输入框】EditText")
        override val qunfaSend2Node = NodeInfo("", "com.tencent.mm:id/b0f", "点击群发页底部功能区的【发送】按钮后的第二个发送按钮")
        //相册页
        override val xiangceCheckBoxNode = NodeInfo("", "com.tencent.mm:id/jdh", "相册区的【checkBox】按钮")
        override val xiangceSendNode = NodeInfo("", "com.tencent.mm:id/kaq", "相册区底部的【发送】按钮")
        override val xiangceImageButtonNode = NodeInfo("^未选中[\\s,，]*原图[\\s,，]*复选框$", "com.tencent.mm:id/km5", "相册区底部的【原图】按钮")

        //群发助手
        override val QFZS_ListViewNode = NodeInfo("", "com.tencent.mm:id/jcf", "群发助手页中间的【ListView】")
        override val QFZS_NewQunFaNode = NodeInfo("", "com.tencent.mm:id/jcv", "群发助手页底部的【新建群发】按钮")
        //选择朋友
        override val XZPY_TagimportNode = NodeInfo("从标签导入", "com.tencent.mm:id/ci2", "选择朋友页中间的【从标签导入】按钮")
        override val XZPY_XuanZhongNode = NodeInfo("", "com.tencent.mm:id/g6_", "选择朋友页底部的【选中】按钮")
        //从标签导入
        override val TAGDR_daoruNode = NodeInfo("导入", "com.tencent.mm:id/fp", "从标签导入页顶部的【导入】按钮")
        override val TAGDR_SousuoNode = NodeInfo("搜索", "com.tencent.mm:id/mmz", "从标签导入页顶部的【搜索】按钮")
        //微信搜索标签页
        override val searchTagEditTextNode = NodeInfo("", "com.tencent.mm:id/d98", "标签搜索界面页顶部的 搜索输入框")
        override val searchTagNode = NodeInfo("", "com.tencent.mm:id/hs8", "标签搜索界面页 找到的标签")




    },

}