package com.google.android.accessibility.selecttospeak

import com.lygttpod.android.auto.service.AccessibilityServiceImp


// https://github.com/ven-coder/Assists
// https://github.com/ven-coder/Assists/issues/12#issuecomment-2684469065
/*
第三次继承
* 每一次打开插件设置，都是一条新的service
    SelectToSpeakService 解决无障碍节点混淆问题
* */
class SelectToSpeakService : AccessibilityServiceImp()
