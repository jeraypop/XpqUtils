package com.lygttpod.android.auto.wx.helper

import android.util.Log
import com.lygttpod.android.auto.wx.data.WxUserInfo
import com.lygttpod.android.auto.wx.em.FriendStatus

object FriendStatusHelper {

    interface TaskCallBack {
        fun onTaskStart(taskName: String)
        fun onTaskEnd(totalTime: Long)
    }

    var taskCallBack: TaskCallBack? = null

    //好友状态检测结果
    private val userResultList = mutableListOf<WxUserInfo>()


    //上次检测到的好友，【假转账方法中「续捡」要用】
    var lastCheckUser: WxUserInfo? = null

    //上次备注点击的好友，【改备注「续捡」要用】
    var lastTagUserCount = 0

    //上次拉群检测到的好友，【拉群方法中「续捡」要用】
    var checkGroupCount = 0

    fun reset() {
        lastCheckUser = null
        lastTagUserCount = 0
        checkGroupCount = 0
        clearFriendsStatusList()
    }

    fun addCheckResult(data: WxUserInfo) {
        Log.d("检查结果", "$data")
        val find = userResultList.indexOfFirst { it.nickName == data.nickName }
        if (find == -1) {
            userResultList.add(data)
        } else {
            userResultList[find] = data
        }
    }

    fun addCheckResults(list: MutableList<WxUserInfo>?) {
        list ?: return
        Log.d("检查结果", "${list.map { it.toString() + "\n" }}")
        userResultList.addAll(list)
    }

    fun addFriends(list: List<String>) {
        userResultList.clear()
        userResultList.addAll(list.map { WxUserInfo(it) })
    }

    fun clearFriendsStatusList() = userResultList.clear()

    fun getUserResultList() = userResultList
    /*
    * 异常   筛选出 状态既不是NORMAL也不是UNKNOW
    * */
    fun filterNotNormalData(): MutableList<WxUserInfo> {
        return userResultList.filterNot { it.status == FriendStatus.NORMAL || it.status == FriendStatus.UNKNOW }
            .toMutableList()
    }
    /*
    * 待检测
    * */
    fun filterUnCheckData(): MutableList<WxUserInfo> {
        return userResultList.filter { it.status == FriendStatus.UNKNOW }
            .toMutableList()
    }
     /*
     * 整个集合
     * */
    fun filterAllData(): MutableList<WxUserInfo> {
        return userResultList
    }

    /*
    * 被拉黑
    * */
    fun filterBlackData(): MutableList<WxUserInfo> {
        return userResultList.filter { it.status == FriendStatus.BLACK }
            .toMutableList()
    }
    /*
    * 被删除
    * */
    fun filterDeleteData(): MutableList<WxUserInfo> {
        return userResultList.filter { it.status == FriendStatus.DELETE }
            .toMutableList()
    }

    /*
    * 账号异常
    * */
    fun filterAccountExceptionData(): MutableList<WxUserInfo> {
        return userResultList.filter { it.status == FriendStatus.ACCOUNT_EXCEPTION }
            .toMutableList()
    }


}