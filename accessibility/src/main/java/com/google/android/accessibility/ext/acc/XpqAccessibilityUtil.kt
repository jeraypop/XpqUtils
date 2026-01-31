package com.google.android.accessibility.ext.acc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.accessibility.ext.utils.LibCtxProvider.Companion.appContext
import com.google.android.accessibility.selecttospeak.accessibilityService
import kotlinx.coroutines.delay

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


    suspend fun pasteTextSuspend(
        context: Context = appContext,
        node: AccessibilityNodeInfo?,
        text: String,
        delayMs: Long = 500
    ): Boolean {
        if (node == null || !node.isEnabled) return false
        if (node.text?.toString() == text) {
            //Log.e("是否输入", "已经成功: "+node.text?.toString() )
            return true
        }

        // 1️⃣  写剪贴板  ""空字符串 目的也就是清空
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("label", ""))
        // 2️⃣ focus + paste
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        delay(delayMs)
        // 1️⃣ 写剪贴板
        clipboard.setPrimaryClip(ClipData.newPlainText("label", text))
        // 2️⃣ focus + paste
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_PASTE)

        // 3️⃣ 等待 UI 线程处理
        delay(delayMs)
        val sig = node.nodeSignature()
        val latestNode = findEditText{
            it.className == sig.className &&
                    Rect().also { r -> it.getBoundsInScreen(r) } == sig.bounds
        }
        val after = latestNode?.text?.toString()

        return after == text
    }

    suspend fun inputTextSuspend(
        context: Context  = appContext,
        node: AccessibilityNodeInfo?,
        text: String,
        delayMs: Long = 500
    ): Boolean {
        if (node == null || !node.isEditable || !node.isEnabled) return false

        if (node.text?.toString() == text) {
            return true
        }

        // 1️⃣ 优先 SET_TEXT
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            delay(delayMs)
            val sig = node.nodeSignature()
            val latestNode = findEditText{
                it.className == sig.className &&
                        Rect().also { r -> it.getBoundsInScreen(r) } == sig.bounds
            }

            if (latestNode?.text?.toString() == text) {
                //Log.e("是否输入", "成功: "+latestNode?.text?.toString() )
                return true
            }else{
                //Log.e("是否输入", "失败: "+latestNode?.text?.toString() )
            }
        }

        val sig = node.nodeSignature()
        val latestNode = findEditText{
            it.className == sig.className &&
                    Rect().also { r -> it.getBoundsInScreen(r) } == sig.bounds
        }

        // 2️⃣ fallback PASTE
        return pasteTextSuspend(context, latestNode,text,delayMs)
    }







}

data class NodeSignature(
    val className: CharSequence?,
    val bounds: Rect ,
    val viewIdResourceName: String?,
    val text: CharSequence?,
    val contentDescription: CharSequence?
)

fun AccessibilityNodeInfo.nodeSignature(): NodeSignature {
    val rect = Rect()
    getBoundsInScreen(rect)
    return NodeSignature(className, rect,viewIdResourceName,text,contentDescription)
}
