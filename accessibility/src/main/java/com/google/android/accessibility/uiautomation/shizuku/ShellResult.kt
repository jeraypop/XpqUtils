package com.google.android.accessibility.uiautomation.shizuku

import android.os.Parcel
import android.os.Parcelable

/**
 * shell 命令执行结果。对应 acclib 中既有的 ShellResult 结构。
 */
class ShellResult : Parcelable {
    var exitCode: Int = 0
    var stdout: String = ""
    var stderr: String = ""

    constructor()

    private constructor(parcel: Parcel) {
        exitCode = parcel.readInt()
        stdout = parcel.readString() ?: ""
        stderr = parcel.readString() ?: ""
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(exitCode)
        parcel.writeString(stdout)
        parcel.writeString(stderr)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ShellResult> {
        override fun createFromParcel(parcel: Parcel): ShellResult = ShellResult(parcel)
        override fun newArray(size: Int): Array<ShellResult?> = arrayOfNulls(size)
    }
}
