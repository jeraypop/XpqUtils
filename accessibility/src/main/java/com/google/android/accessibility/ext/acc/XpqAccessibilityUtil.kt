package com.google.android.accessibility.ext.acc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.accessibility.selecttospeak.accessibilityService

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/1/31  18:32
 * Description:This is XpqAccessibilityUtil
 */
object XpqAccessibilityUtil {
    //查找第一个 EditText
    @JvmStatic
    @JvmOverloads
    fun findFirstEditText(node: AccessibilityNodeInfo? = accessibilityService?.rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null) return null

        // 1️⃣ 当前节点就是 EditText
        if (node.className == "android.widget.EditText") {
            return node
        }

        // 2️⃣ DFS 遍历子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val result = findFirstEditText(child)
            if (result != null) return result
        }

        return null
    }
   //查找 所有 EditText
   @JvmStatic
   @JvmOverloads
   fun findAllEditTexts(
       node: AccessibilityNodeInfo? = accessibilityService?.rootInActiveWindow,
       result: MutableList<AccessibilityNodeInfo> = mutableListOf()
   ): List<AccessibilityNodeInfo> {

       if (node == null) return result

       if (node.className == "android.widget.EditText") {
           result.add(node)
       }

       for (i in 0 until node.childCount) {
           findAllEditTexts(node.getChild(i), result)
       }

       return result
   }
     /*
     * 使用示例
     * val edit = findEditText(root) { it.hintText?.toString()?.contains("手机号") == true }
     *
     * val edit = findEditText(root) { it.text?.toString()?.contains("手机号") == true }
     *
     * val edit = findEditText(root) {  it.viewIdResourceName == "com.xxx.app:id/et_phone" }
     *
     * val edit = findEditText(root) {  it.isEditable && it.isFocusable }
     *
     * */
    //通用版本 条件过滤
     @JvmStatic
     @JvmOverloads
    fun findEditText(
        node: AccessibilityNodeInfo? = accessibilityService?.rootInActiveWindow,
        matcher: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (node == null) return null

        if (node.className == "android.widget.EditText" && matcher(node)) {
            return node
        }

        for (i in 0 until node.childCount) {
            val result = findEditText(node.getChild(i), matcher)
            if (result != null) return result
        }

        return null
    }

/*    editNode?.let {
        it.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        setText(it, "13800138000")
    }*/

    //自动填写文本
    @JvmStatic
    @JvmOverloads
    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

  /*  if (!setText(it, "13800138000")) {
        pasteText(context, it, "13800138000")
    }*/

    // 兜底粘贴  某些银行 / WebView 内输入框 只能用这个
    @JvmStatic
    @JvmOverloads
    fun pasteText(
        context: Context,
        node: AccessibilityNodeInfo,
        text: String
    ) {
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("label", text))

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }





}