package com.lygttpod.android.auto.wx.data

import com.lygttpod.android.auto.wx.em.FriendStatus

data class SendUserInfo(
    val sender: String,
    val sendContent: String,
    val isPicture: Boolean = false,
    val startIndex: Int = 0,
    val countTotal: Int = 1,
    var status: FriendStatus = FriendStatus.UNKNOW
) {
    override fun toString(): String {
        return "sender: $sender → sendContent: $sendContent → isPicture: $isPicture → startIndex: $startIndex → countTotal: $countTotal → status: ${status.status}"
    }
}
