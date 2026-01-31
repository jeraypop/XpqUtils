package com.google.android.accessibility.ext.acc

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.accessibility.ext.default
import com.google.android.accessibility.selecttospeak.SelectToSpeakServiceAbstract.Companion.copyNodeCompat
import com.google.android.accessibility.selecttospeak.SelectToSpeakServiceAbstract.Companion.recycleCompat
import kotlinx.coroutines.delay

//fun AccessibilityService.findById(id: String): AccessibilityNodeInfo? {
//    return rootInActiveWindow?.findNodesById(id)?.firstOrNull()
//}
fun AccessibilityService.findById(id: String): AccessibilityNodeInfo? {
    val raw = rootInActiveWindow
        ?.findNodesById(id)
        ?.firstOrNull()
        ?: return null

    val safe = copyNodeCompat(raw)
    recycleCompat(raw)
    return safe
}




//fun AccessibilityService?.findNodesById(id: String): List<AccessibilityNodeInfo> {
//    return this?.rootInActiveWindow?.findNodesById(id) ?: listOf()
//}
fun AccessibilityService?.findNodesById(id: String): List<AccessibilityNodeInfo> {
    this ?: return emptyList()
    val rawList = rootInActiveWindow?.findNodesById(id) ?: return emptyList()

    if (rawList.isEmpty()) return emptyList()

    val result = ArrayList<AccessibilityNodeInfo>(rawList.size)
    for (raw in rawList) {
        copyNodeCompat(raw)?.let { result.add(it) }
        recycleCompat(raw)
    }
    return result
}



//fun AccessibilityService.findByText(text: String): AccessibilityNodeInfo? {
//    return rootInActiveWindow?.findNodeByText(text)
//}

fun AccessibilityService.findByText(text: String): AccessibilityNodeInfo? {
    val raw = rootInActiveWindow
        ?.findNodeByText(text)
        ?: return null

    val safe = copyNodeCompat(raw)
    recycleCompat(raw)
    return safe
}



//fun AccessibilityService.findByContainsText(
//    isPrint: Boolean = true,
//    textList: List<String>
//): AccessibilityNodeInfo? {
//    return rootInActiveWindow?.findNodeWrapperByContainsText(isPrint, textList)?.nodeInfo
//}

fun AccessibilityService.findByContainsText(
    isPrint: Boolean = true,
    textList: List<String>
): AccessibilityNodeInfo? {
    val raw = rootInActiveWindow
        ?.findNodeWrapperByContainsText(isPrint, textList)
        ?.nodeInfo
        ?: return null

    val safe = copyNodeCompat(raw)
    recycleCompat(raw)
    return safe
}



//fun AccessibilityService.findByIdAndText(id: String, text: String): AccessibilityNodeInfo? {
//    return rootInActiveWindow?.findNodesById(id)?.firstOrNull { it.text == text }
//}

fun AccessibilityService.findByIdAndText(id: String, text: String): AccessibilityNodeInfo? {
    val raw = rootInActiveWindow
        ?.findNodesById(id)
        ?.firstOrNull { it.text == text }
        ?: return null

    val safe = copyNodeCompat(raw)
    recycleCompat(raw)
    return safe
}

fun AccessibilityService.findByIdAndTextToUser(id: String, text: String): AccessibilityNodeInfo? {
    val raw = rootInActiveWindow
        ?.findNodesById(id)
        ?.firstOrNull { it.text == text && it.isVisibleToUser }
        ?: return null

    val safe = copyNodeCompat(raw)
    recycleCompat(raw)
    return safe
}



//fun AccessibilityService?.clickById(id: String, gestureClick: Boolean = true): Boolean {
//    this ?: return false
//    val find = rootInActiveWindow?.findNodesById(id)?.firstOrNull() ?: return false
//    return if (gestureClick) {
//        gestureClick(find).takeIf { it } ?: find.click()
//    } else {
//        find.click().takeIf { it } ?: gestureClick(find)
//    }
//}

fun AccessibilityService?.clickById(
    id: String,
    gestureClick: Boolean = true
): Boolean {
    this ?: return false

    val raw = rootInActiveWindow
        ?.findNodesById(id)
        ?.firstOrNull()
        ?: return false

    val node = copyNodeCompat(raw)
    recycleCompat(raw)

    node ?: return false

    return try {
        if (gestureClick) {
            gestureClick(node).takeIf { it } ?: node.click()
        } else {
            node.click().takeIf { it } ?: gestureClick(node)
        }
    } finally {
        recycleCompat(node)
    }
}



//fun AccessibilityService?.clickByText(text: String, gestureClick: Boolean = true): Boolean {
//    this ?: return false
//    val find = rootInActiveWindow?.findNodesByText(text)?.firstOrNull() ?: return false
//    return if (gestureClick) {
//        gestureClick(find).takeIf { it } ?: find.click()
//    } else {
//        find.click().takeIf { it } ?: gestureClick(find)
//    }
//}

fun AccessibilityService?.clickByText(
    text: String,
    gestureClick: Boolean = true
): Boolean {
    this ?: return false

    val raw = rootInActiveWindow
        ?.findNodesByText(text)
        ?.firstOrNull()
        ?: return false

    val node = copyNodeCompat(raw)
    recycleCompat(raw)
    node ?: return false

    return try {
        if (gestureClick) {
            gestureClick(node).takeIf { it } ?: node.click()
        } else {
            node.click().takeIf { it } ?: gestureClick(node)
        }
    } finally {
        recycleCompat(node)
    }
}


//fun AccessibilityService?.clickByCustomRule(gestureClick: Boolean = true, customRule: (AccessibilityNodeInfo) -> Boolean): Boolean {
//    this ?: return false
//    val find = rootInActiveWindow?.findNodeWithCustomRule(isPrint = false) { customRule(it) } ?: return false
//    return if (gestureClick) {
//        gestureClick(find).takeIf { it } ?: find.click()
//    } else {
//        find.click().takeIf { it } ?: gestureClick(find)
//    }
//}

fun AccessibilityService?.clickByCustomRule(
    gestureClick: Boolean = true,
    customRule: (AccessibilityNodeInfo) -> Boolean
): Boolean {
    this ?: return false

    val raw = rootInActiveWindow
        ?.findNodeWithCustomRule(isPrint = false) { customRule(it) }
        ?: return false

    val node = copyNodeCompat(raw)
    recycleCompat(raw)
    node ?: return false

    return try {
        if (gestureClick) {
            gestureClick(node).takeIf { it } ?: node.click()
        } else {
            node.click().takeIf { it } ?: gestureClick(node)
        }
    } finally {
        recycleCompat(node)
    }
}



//fun AccessibilityService?.clickByIdAndText(
//    id: String,
//    text: String,
//    gestureClick: Boolean = true
//): Boolean {
//    this ?: return false
//    rootInActiveWindow?.findNodesById(id)?.firstOrNull { it.text.default() == text }?.let { find ->
//        return if (gestureClick) {
//            gestureClick(find).takeIf { it } ?: find.click()
//        } else {
//            find.click().takeIf { it } ?: gestureClick(find)
//        }
//    }
//    return false
//}

fun AccessibilityService?.clickByIdAndText(
    id: String,
    text: String,
    gestureClick: Boolean = true
): Boolean {
    this ?: return false

    val raw = rootInActiveWindow
        ?.findNodesById(id)
        ?.firstOrNull { it.text.default() == text }
        ?: return false

    val node = copyNodeCompat(raw)
    recycleCompat(raw)
    node ?: return false

    return try {
        if (gestureClick) {
            gestureClick(node).takeIf { it } ?: node.click()
        } else {
            node.click().takeIf { it } ?: gestureClick(node)
        }
    } finally {
        recycleCompat(node)
    }
}


//fun AccessibilityService?.clickByIdAndDesc(
//    id: String,
//    regex: String,
//    gestureClick: Boolean = true
//): Boolean {
//    this ?: return false
//    rootInActiveWindow?.findNodesById(id)?.firstOrNull { it.contentDescription.default().matches(regex.toRegex())
//    }?.let { find ->
//        return if (gestureClick) {
//            gestureClick(find).takeIf { it } ?: find.click()
//        } else {
//            find.click().takeIf { it } ?: gestureClick(find)
//        }
//    }
//    return false
//}

fun AccessibilityService?.clickByIdAndDesc(
    id: String,
    regex: String,
    gestureClick: Boolean = true
): Boolean {
    this ?: return false

    val pattern = regex.toRegex()

    val raw = rootInActiveWindow
        ?.findNodesById(id)
        ?.firstOrNull { it.contentDescription.default().matches(pattern) }
        ?: return false

    val node = copyNodeCompat(raw)
    recycleCompat(raw)
    node ?: return false

    return try {
        if (gestureClick) {
            gestureClick(node).takeIf { it } ?: node.click()
        } else {
            node.click().takeIf { it } ?: gestureClick(node)
        }
    } finally {
        recycleCompat(node)
    }
}



//suspend fun AccessibilityService?.clickMultipleByIdAndText(
//    id: String,
//    text: String,
//    startIndex: Int = 0, // 新增偏移逻辑，默认0保持原有逻辑
//    count: Int = 1, // 新增数量参数，默认1保持原有逻辑
//    gestureClick: Boolean = true
//): Boolean {
//    this ?: return false
//    // 获取前N个匹配元素
//    val targets = rootInActiveWindow?.findNodesById(id)
//        ?.filter { it.text.default() == text }
//        ?.drop(startIndex)  //
//        ?.take(count)
//        ?: return false
//    var b = false
//    targets.forEach { find ->
//        b = if (gestureClick) {
//            gestureClick(find) || find.click()
//        } else {
//            find.click() || gestureClick(find)
//        }
//        delay(500) // 增加间隔
//    }
//    return b
//
//}

suspend fun AccessibilityService?.clickMultipleByIdAndText(
    id: String,
    text: String,
    startIndex: Int = 0,
    count: Int = 1,
    gestureClick: Boolean = true
): Boolean {
    this ?: return false

    val rawList = rootInActiveWindow
        ?.findNodesById(id)
        ?.filter { it.text.default() == text }
        ?.drop(startIndex)
        ?.take(count)
        ?: return false

    if (rawList.isEmpty()) return false

    var result = false

    for (raw in rawList) {
        val node = copyNodeCompat(raw)
        recycleCompat(raw)
        node ?: continue

        try {
            result = if (gestureClick) {
                gestureClick(node) || node.click()
            } else {
                node.click() || gestureClick(node)
            }
        } finally {
            recycleCompat(node)
        }

        delay(500)
    }

    return result
}



//fun AccessibilityService?.clickByIdAndTextFilter(
//    id: String,
//    text: String,
//    gestureClick: Boolean = true
//): Boolean {
//    this ?: return false
//    rootInActiveWindow?.findNodesById(id)?.firstOrNull {
//        it.text.default(filter = true) == text }?.let { find ->
//        return if (gestureClick) {
//            gestureClick(find).takeIf { it } ?: find.click()
//        } else {
//            find.click().takeIf { it } ?: gestureClick(find)
//        }
//    }
//    return false
//}

fun AccessibilityService?.clickByIdAndTextFilter(
    id: String,
    text: String,
    gestureClick: Boolean = true
): Boolean {
    this ?: return false

    val raw = rootInActiveWindow
        ?.findNodesById(id)
        ?.firstOrNull { it.text.default(filter = true) == text }
        ?: return false

    val node = copyNodeCompat(raw)
    recycleCompat(raw)
    node ?: return false

    return try {
        if (gestureClick) {
            gestureClick(node).takeIf { it } ?: node.click()
        } else {
            node.click().takeIf { it } ?: gestureClick(node)
        }
    } finally {
        recycleCompat(node)
    }
}


//suspend fun AccessibilityService?.scrollToClickByText(
//    scrollViewId: String,
//    text: String
//): Boolean {
//    this ?: return false
//    val find = rootInActiveWindow?.findNodeByText(text)
//    return if (find == null) {
//        rootInActiveWindow?.findNodesById(scrollViewId)?.firstOrNull()?.scrollForward()
//        delay(200)
//        scrollToClickByText(scrollViewId, text)
//    } else {
//        find.click()
//    }
//}

suspend fun AccessibilityService?.scrollToClickByText(
    scrollViewId: String,
    text: String
): Boolean {
    this ?: return false

    val raw = rootInActiveWindow?.findNodeByText(text)

    if (raw != null) {
        val node = copyNodeCompat(raw)
        recycleCompat(raw)
        node ?: return false

        return try {
            node.click()
        } finally {
            recycleCompat(node)
        }
    }

    val scrollRaw = rootInActiveWindow
        ?.findNodesById(scrollViewId)
        ?.firstOrNull()

    val scrolled = scrollRaw?.scrollForward() ?: false
    recycleCompat(scrollRaw)

    if (!scrolled) return false

    delay(200)
    return scrollToClickByText(scrollViewId, text)
}

/**
 * 模拟back按键
 */
fun AccessibilityService.pressBackButton(): Boolean {
    return performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
}

suspend fun AccessibilityService?.scrollToFindNextNodeByCurrentText(
    scrollViewId: String,
    childViewId: String,
    lastText: String?,
    filterTexts: List<String> = listOf()
): AccessibilityNodeInfo? {
    this ?: return null
    var find = getNextNodeByCurrentText(scrollViewId, childViewId, lastText, filterTexts)
    var isEnd = false
    while (find == null && !isEnd) {
        val parent: AccessibilityNodeInfo =
            rootInActiveWindow?.findNodeById(scrollViewId) ?: return null
        parent.scrollForward()
        delay(200)
        Log.d("FindNextNodeByCurrentText", "滚动一屏后继续去查找【$lastText】的 next")
        val tryFind = getNextNodeByCurrentText(scrollViewId, childViewId, lastText, filterTexts)
        find = tryFind
        isEnd = tryFind == null
        if (find == null && isEnd) {
            Log.d("FindNextNodeByCurrentText", "屏幕已经滚动到底了，依然没找到，判定为查询结束")
        }
    }
    val result = copyNodeCompat(find)
    recycleCompat(find)
    return result
}






suspend fun AccessibilityService?.scrollToFindNextNodeByCurrentText_Tag(
    scrollViewId: String,
    childViewId: String,
    lastText: String,
    filterTexts: List<String> = listOf()
): AccessibilityNodeInfo? {
    this ?: return null
    var find = getNextNodeByCurrentText_Tag(scrollViewId, childViewId, lastText, filterTexts)
    //============
//     clickByIdAndText()
    val list = mutableListOf<AccessibilityNodeInfo>()
    val finds = findAllChildByFilter(scrollViewId, childViewId) { filter ->
        //倒叙查找可以提示查找效率，因为新增的数据是在列表后边的
        list.findLast { it.text.default() == filter.text.default() } != null
    }
    list.addAll(finds)



    //============
    var isEnd = false
    while (find == null && !isEnd) {
        val parent: AccessibilityNodeInfo =
            rootInActiveWindow?.findNodeById(scrollViewId) ?: return null
        parent.scrollForward()
        delay(200)
        Log.d("FindNextNodeByCurrentText", "滚动一屏后继续去查找【$lastText】的 next")
        val tryFind = getNextNodeByCurrentText_Tag(scrollViewId, childViewId, lastText, filterTexts)
        find = tryFind
//        isEnd = tryFind == null
//        if (find == null && isEnd) {
//            Log.d("FindNextNodeByCurrentText", "屏幕已经滚动到底了，依然没找到，判定为查询结束")
//        }
        //===
        val findNextNodes = findAllChildByFilter(scrollViewId, childViewId) { filter ->
            list.findLast { it.text.default() == filter.text.default() } != null
        }
        isEnd = findNextNodes.isEmpty()
        if (isEnd) break
        list.addAll(findNextNodes)
        //===




    }

    val result = copyNodeCompat(find)
    recycleCompat(find)
    return result

}

private fun AccessibilityService?.getNextNodeByCurrentText_Tag(
    scrollViewId: String,
    childViewId: String,
    lastText: String?,
    filterTexts: List<String> = listOf()
): AccessibilityNodeInfo? {
    this ?: return null
    val parent: AccessibilityNodeInfo = rootInActiveWindow?.findNodeById(scrollViewId) ?: return null
    val find =
        parent.findNodesById(childViewId).filterNot { filterTexts.contains(it.text.default().trim()) }
    return if (lastText.isNullOrBlank()) {
        val first = find.firstOrNull()
        Log.d("FindNextNodeByCurrentText", "没有lastText，取过滤后列表中可用的第一个【${first?.text.default()}】")
        first
    } else {
        find.find { it.text.default().contains(lastText) && it.text.default().trim() == lastText.default().trim()  }
        //注意：有的好友设置了微信状态，在通讯录列表用户名后边会显示微信状态图标
        //这个时候取到是昵称后边带空格(空格好像就是状态)，所有用contains保险一下
        //断点调试了好久才发现这个【微信状态】问题
/*        val lastIndex = find.indexOfFirst { it.text.default().contains(lastText) && it.text.default().trim() == lastText.default().trim() }
        if (lastIndex > -1) {
            val next = find.getOrNull(lastIndex)
            Log.d("FindNextNodeByCurrentText", "找到了上次检测的【$lastText】，取【$lastText】的 next【${next?.text.default()}】")
            next
        } else {
//            find.firstOrNull()
            return null
        }*/
    }
}

private fun AccessibilityService?.getNextNodeByCurrentText(
    scrollViewId: String,
    childViewId: String,
    lastText: String?,
    filterTexts: List<String> = listOf()
): AccessibilityNodeInfo? {
    this ?: return null
    val parent: AccessibilityNodeInfo = rootInActiveWindow?.findNodeById(scrollViewId) ?: return null
    val find =
        parent.findNodesById(childViewId).filterNot { filterTexts.contains(it.text.default().trim()) }
    return if (lastText.isNullOrBlank()) {
        val first = find.firstOrNull()
        Log.d("FindNextNodeByCurrentText", "没有lastText，取过滤后列表中可用的第一个【${first?.text.default()}】")
        first
    } else {
        //注意：有的好友设置了微信状态，在通讯录列表用户名后边会显示微信状态图标
        //这个时候取到是昵称后边带空格(空格好像就是状态)，所有用contains保险一下
        //断点调试了好久才发现这个【微信状态】问题
        val lastIndex = find.indexOfFirst { it.text.default().contains(lastText) && it.text.default().trim() == lastText.default().trim() }
        if (lastIndex > -1) {
            val next = find.getOrNull(lastIndex + 1)
            Log.d("FindNextNodeByCurrentText", "找到了上次检测的【$lastText】，取【$lastText】的 next【${next?.text.default()}】")
            next
        } else {
            find.firstOrNull()
        }
    }
}




suspend fun AccessibilityService?.scrollToFindByText(
    scrollViewId: String,
    text: String
): AccessibilityNodeInfo? {
    this ?: return null
    val find = rootInActiveWindow?.findNodeByText(text)
    return if (find == null) {
        rootInActiveWindow?.findNodeById(scrollViewId)?.scrollForward()
        delay(200)
        scrollToFindByText(scrollViewId, text)
    } else {
        val result = copyNodeCompat(find)
        recycleCompat(find)
        result

    }
}




fun AccessibilityService?.printNodeInfo(simplePrint: Boolean = true): String {
    this ?: return ""
    return rootInActiveWindow?.printNodeInfo(simplePrint = simplePrint).toString()
}



suspend fun AccessibilityService?.findAllChildByScroll(
    parentViewId: String,
    childViewId: String,
): List<AccessibilityNodeInfo> {
    this ?: return listOf()
    val rootNode = rootInActiveWindow
    val list = mutableListOf<AccessibilityNodeInfo>()
    val finds = findAllChildByFilter(parentViewId, childViewId) { filter ->
        //倒叙查找可以提示查找效率，因为新增的数据是在列表后边的
        list.findLast { it.text.default() == filter.text.default() } != null
    }
    list.addAll(finds)
    val parentNode = rootNode?.findNodeById(parentViewId) ?: return list
    var isStop = false
    val timeL = 600L
    val attempts = 4
    while (parentNode.isScrollable && !isStop) {
        parentNode.scrollForward()

        delay(timeL)//时间太短的话有时候会获取不到节点信息
        //==================
        //==================

        var findNextNodes = findNextNodeInfos(parentViewId, childViewId, list)

        for (i in 1..attempts) {
            if (findNextNodes.isEmpty()) {
                delay(timeL) // 时间太短的话有时候会获取不到节点信息 如果findNextNodes为空，则延迟timeL毫秒
                findNextNodes = findNextNodeInfos(parentViewId, childViewId, list)
            } else {
                break // 如果findNextNodes非空，则退出循环
            }
        }


        isStop = findNextNodes.isEmpty()
        if (isStop){
            break
        }
        list.addAll(findNextNodes)
        //```````````````````
    }
    val result = list.mapNotNull { copyNodeCompat(it) }
    list.forEach { recycleCompat(it) }
    return result

}

private fun AccessibilityService.findNextNodeInfos(
    parentViewId: String,
    childViewId: String,
    list: MutableList<AccessibilityNodeInfo>
) = findAllChildByFilter(parentViewId, childViewId) { filter ->
    list.findLast { it.text?.default() == filter?.text?.default() } != null
}

private fun AccessibilityService?.findAllChildByFilter(
    parentViewId: String,
    childViewId: String,
    filterPredicate: (AccessibilityNodeInfo) -> Boolean
): List<AccessibilityNodeInfo> {
    val find = findChildNodes(parentViewId, childViewId)
    return find.filterNot { filterPredicate(it) }
}


fun AccessibilityService?.findChildNodes(
    parentViewId: String,
    childViewId: String
): List<AccessibilityNodeInfo> {
    this ?: return listOf()
    val rootNode = rootInActiveWindow
    val parentNode: AccessibilityNodeInfo =
        rootNode?.findNodesById(parentViewId)?.firstOrNull() ?: return listOf()
    val findList = mutableListOf<AccessibilityNodeInfo>()
    val size = parentNode.childCount
    if (size <= 0) return emptyList()
    for (index in 0 until size) {
        parentNode?.getChild(index)?.findNodesById(childViewId)?.firstOrNull()?.let {
            findList.add(it)
        }
    }
    return findList
}

suspend fun AccessibilityService?.selectChild(
    parentViewId: String,
    childViewId: String,
    maxSelectCount: Int = Int.MAX_VALUE,
    lastText: String? = null,
): List<String> {
    this ?: return listOf()
    val findTexts = mutableListOf<String>()
    val findNodes = findChildNodes(parentViewId, childViewId)
    val findIndex = findNodes.indexOfFirst { it.text.default() == lastText }
    findNodes.filterIndexed { index, _ -> index > findIndex }.forEach {
        val text = it.text.default()
        if (!findTexts.contains(text)) {
            if (findTexts.size < maxSelectCount) {
                val clicked = it.click()
                if (clicked) {
                    findTexts.add(text)
                    Log.d("selectChildByScroll", "click: 点击 $text")
                }
                delay(50)
            } else {
                return@forEach
            }
        }
    }
    return findTexts
}

suspend fun AccessibilityService?.selectChildByScroll(
    parentViewId: String,
    childViewId: String,
    maxSelectCount: Int = Int.MAX_VALUE,
    lastText: String? = null,
): List<String> {
    this ?: return listOf()
    val rootNode = rootInActiveWindow
    val findTexts = mutableListOf<String>()
    val select = if (lastText.isNullOrBlank()) {
        selectChild(parentViewId, childViewId, maxSelectCount, lastText)
    } else {
        scrollToFindByText(parentViewId, lastText)
        selectChild(parentViewId, childViewId, maxSelectCount, lastText)
    }
    findTexts.addAll(select)
    if (findTexts.size == maxSelectCount) return findTexts
    val parentNode = rootNode?.findNodeById(parentViewId) ?: return findTexts
    var isEnd = false
    val timeL = 1000L
    val attempts = 5
    while (parentNode.isScrollable && findTexts.size < maxSelectCount && !isEnd) {
        parentNode.scrollForward()
        Log.d("selectChildByScroll", "滚动了一屏")
        delay(timeL)
        var findNextNodes = findNextNodeSelect(parentViewId, childViewId, maxSelectCount, findTexts, lastText)

        for (i in 1..attempts) {
            if (findNextNodes.isEmpty()) {
                delay(timeL)
                findNextNodes = findNextNodeSelect(parentViewId, childViewId, maxSelectCount, findTexts, lastText)
            } else {
                break // 如果findNextNodes不为空，则跳出循环
            }
        }


        isEnd = findNextNodes.isEmpty()
        Log.d("selectChildByScroll", "=============是否搜索到底了  isEnd = $isEnd")
        findTexts.addAll(findNextNodes)
    }
    return findTexts
}

private suspend fun AccessibilityService.findNextNodeSelect(
    parentViewId: String,
    childViewId: String,
    maxSelectCount: Int,
    findTexts: MutableList<String>,
    lastText: String?
) = selectChild(
    parentViewId,
    childViewId,
    maxSelectCount - findTexts.size,
    findTexts.lastOrNull() ?: lastText
)


