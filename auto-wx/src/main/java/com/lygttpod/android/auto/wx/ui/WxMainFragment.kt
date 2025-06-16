package com.lygttpod.android.auto.wx.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.AdapterView.OnItemSelectedListener
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.accessibility.ext.openAccessibilitySetting
import com.lygttpod.android.auto.wx.R
import com.lygttpod.android.auto.wx.adapter.FriendInfoAdapter
import com.lygttpod.android.auto.wx.data.SendUserInfo
import com.lygttpod.android.auto.wx.databinding.FragmentWxMainBinding
import com.lygttpod.android.auto.wx.em.FriendStatus
import com.lygttpod.android.auto.wx.helper.FriendStatusHelper
import com.lygttpod.android.auto.wx.helper.TaskBySearchSendHelper
import com.lygttpod.android.auto.wx.helper.ToastUtil
import com.lygttpod.android.auto.wx.ktx.formatTime
import com.lygttpod.android.auto.wx.service.WXAccessibility
import com.lygttpod.android.auto.wx.version.currentWXVersion
import com.lygttpod.android.auto.wx.version.wechatVersionArray


class WxMainFragment : Fragment() {

    private var _binding: FragmentWxMainBinding? = null

    private val binding get() = _binding!!

    private val adapter = FriendInfoAdapter()

    private val accServiceLiveData = MutableLiveData<Boolean>()
    private val taskEndLiveData = MutableLiveData<Long>()
    private val taskStartLiveData = MutableLiveData<String>()
    private val listLiveData = MutableLiveData<Boolean>()

    var isNotShowTime = true



    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = FragmentWxMainBinding.inflate(inflater, container, false)

        return binding.root

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initRecyclerView()
        initListener()
        initObserver()

    }

    private fun initRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
    }

    private fun showData() {
        if (isNotShowTime){
            if (FriendStatusHelper.getUserResultList().isNotEmpty()){
                binding.tvTaskDes.text = "全部好友数: "+FriendStatusHelper.getUserResultList().size.toString()

            }else{
                binding.tvTaskDes.text = ""

            }

        }
        isNotShowTime = true
        adapter.setData(FriendStatusHelper.getUserResultList())
        binding.clFilter.visibility =
            if (FriendStatusHelper.getUserResultList().isNotEmpty()) View.VISIBLE else View.GONE
    }


    override fun onResume() {
        super.onResume()

        WXAccessibility.isInWXApp.set(false)
        //SelectToSpeakService
        //WXAccessibility
//        accServiceLiveData.value =
//            requireContext().isAccessibilityOpened(SelectToSpeakService::class.java)

        accServiceLiveData.value = WXAccessibility.service != null

        listLiveData.value = FriendStatusHelper.filterNotNormalData().isNotEmpty()
        showData()
    }

    private fun initObserver() {
        listLiveData.observe(viewLifecycleOwner) { hasData ->
            binding.btnTag.isEnabled = hasData    
    }
        accServiceLiveData.observe(viewLifecycleOwner) { open ->
//            binding.chAutoHb.isEnabled = open
            binding.btnGetFriendList.isEnabled = open
            binding.btnCheck.isEnabled = open
//            binding.btnCheckByGroup.isEnabled = open


            binding.btnOpenService.text = if (open) "❶辅助服务已开" else "❶辅助服务未开"


        }

        taskStartLiveData.observe(viewLifecycleOwner) { taskName ->
            binding.tvTaskDes.text = taskName
            isNotShowTime = false
        }

        taskEndLiveData.observe(viewLifecycleOwner) { totalTime ->
            binding.tvTaskDes.text = "耗时：${totalTime.formatTime()}"+"  数量：" +FriendStatusHelper.getUserResultList().size
            binding.clFilter.visibility = View.VISIBLE
        }

        FriendStatusHelper.taskCallBack = object : FriendStatusHelper.TaskCallBack {

            override fun onTaskStart(taskName: String) {
                taskStartLiveData.postValue(taskName)
            }

            override fun onTaskEnd(totalTime: Long) {
                taskEndLiveData.postValue(totalTime)
            }

        }
    }



    private fun initListener() {
        binding.btnOpenService.setOnClickListener {

            showCustomImageDialog(requireActivity())
        }

        binding.btnWxVersion.adapter =
            ArrayAdapter(requireContext(), R.layout.item_version, wechatVersionArray)

        binding.btnWxVersion.onItemSelectedListener = object : OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                currentWXVersion = wechatVersionArray[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }

        }

        binding.btnGetFriendList.setOnClickListener {
            if (WXAccessibility.getWXService()!=null){
//                showTipDialog()
                val sendList: MutableList<SendUserInfo> = ArrayList()
                sendList.add(
                    SendUserInfo(
                        "奋斗",
                        "你好",
                        false,
                        0,
                        1,
                        FriendStatus.UNKNOW
                    )
                )
                TaskBySearchSendHelper.startTask(requireContext().applicationContext, sendList, false)
            }else{
                ToastUtil.toast(requireContext(), "无障碍服务被关闭,请重启下本软件,再开启")
            }

        }
        //检测好友
        binding.btnCheck.setOnClickListener {
            if (WXAccessibility.getWXService()!=null){
                showCheckFriendDialog()
            }else{
                ToastUtil.toast(requireContext(), "无障碍服务被关闭,请重启下本软件,再开启")
            }

        }

        //追加备注
        binding.btnTag.setOnClickListener {
            if (WXAccessibility.getWXService()!=null){
                showTagTipDialog()
            }else{
                ToastUtil.toast(requireContext(), "无障碍服务被关闭,请重启下本软件,再开启")
            }

        }

//        binding.btnCheckByGroup.setOnClickListener {
//            clear()
//            TaskByGroupHelper.startTask(requireContext().applicationContext)
//        }


//        binding.chAutoHb.setOnCheckedChangeListener { buttonView, isChecked ->
//            HBTaskHelper.autoFuckMoney(isChecked)
//        }

//        binding.btnFilterAll.setOnClickListener {
//            binding.tvTaskDes.text = "全部好友数: "+FriendStatusHelper.filterAllData().size.toString()
//            //整个集合
//            adapter.setData(FriendStatusHelper.filterAllData())
//        }

//        binding.btnFilterNotNormal.setOnClickListener {
//            binding.tvTaskDes.text = "异常好友数: "+FriendStatusHelper.filterNotNormalData().size.toString()
//            //异常集合
//            adapter.setData(FriendStatusHelper.filterNotNormalData())
//            Log.e("集合", "data4= "+FriendStatusHelper.filterNotNormalData().size)
//        }

//        binding.btnFilterUncheck.setOnClickListener {
//            binding.tvTaskDes.text = "待检测好友数: "+FriendStatusHelper.filterUnCheckData().size.toString()
//            //待检测
//            adapter.setData(FriendStatusHelper.filterUnCheckData())
//        }


        val style = SpannableStringBuilder()

        //设置文字
        style.append("单机不联网,无任何安全隐患\n更多实用好玩软件,尽在公众号: 消屏器")

        //设置部分文字点击事件
        val danJISpan: ClickableSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {

            }
            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.isFakeBoldText = true // 设置粗体样式
                ds.color = Color.RED // 设置颜色，如果需要的话
                ds.isUnderlineText = false // 移除下划线
            }
        }

        val gongZhongSpan: ClickableSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("text", "消屏器"))
                Toast.makeText(requireContext(), "已复制 "+"消屏器", Toast.LENGTH_SHORT).show()

            }
            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.isFakeBoldText = true // 设置粗体样式
                ds.color = Color.RED // 设置颜色，如果需要的话
                ds.isUnderlineText = true // 是否有下划线
            }
        }
        style.setSpan(danJISpan, 0, 5, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        style.setSpan(gongZhongSpan, 30, 33, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        //配置给TextView
        binding.tvTip.setMovementMethod(LinkMovementMethod.getInstance())
        binding.tvTip.setText(style)

        val style_Tip = SpannableStringBuilder()

        //设置文字
        style_Tip.append("选择本机 微信版本?  ☞")

        //设置部分文字点击事件
        val tipSpan: ClickableSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                AlertDialog.Builder(requireContext())
                    .setTitle("温馨提示")
                    .setMessage("如果找不到和本机微信版本匹配的,可切换不同的版本试一下\n\n都不行的话,请联系我们适配最新版本")
                    .setCancelable(true)
                    .setNegativeButton("知道了") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .setPositiveButton("好的") { dialog, _ ->

                        dialog.dismiss()
                    }.create().show()
            }
            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.isFakeBoldText = true // 设置粗体样式
                ds.color = Color.RED // 设置颜色，如果需要的话
                ds.isUnderlineText = true // 下划线

            }
        }

        style_Tip.setSpan(tipSpan, 5, 10, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        binding.tvWxVersion.setMovementMethod(LinkMovementMethod.getInstance())
        binding.tvWxVersion.setText(style_Tip)
    }

    private fun clear() {
        FriendStatusHelper.reset()
        adapter.clear()
    }

    private fun showContinueCheckTipDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("温馨提示")
            .setMessage("上次已检测到【${FriendStatusHelper.lastCheckUser?.nickName}】\n\n是否继续检测")
            .setCancelable(true)
            .setNegativeButton("重新检测") { dialog, _ ->
                adapter.clear()
//                TaskHelper.startCheckTask(requireContext().applicationContext, false)
                dialog.dismiss()
            }
            .setPositiveButton("继续检测") { dialog, _ ->
//                TaskHelper.startCheckTask(requireContext().applicationContext, true)
                dialog.dismiss()
            }.create().show()
    }

    private fun showContinueCheckTipDialog_Group() {
        AlertDialog.Builder(requireContext())
            .setTitle("温馨提示")
            .setMessage("上次已检测了【${FriendStatusHelper.checkGroupCount}】个联系人\n\n是否继续检测")
            .setCancelable(true)
            .setNegativeButton("重新检测") { dialog, _ ->
                adapter.clear()
//                TaskByGroupHelper.startTask(requireContext().applicationContext,false)
                dialog.dismiss()
            }
            .setPositiveButton("继续检测") { dialog, _ ->
//                TaskByGroupHelper.startTask(requireContext().applicationContext,true)
                dialog.dismiss()
            }.create().show()
    }

    private fun showContinueCheckTipDialog_Tag() {
        AlertDialog.Builder(requireContext())
            .setTitle("温馨提示")
            .setMessage("还剩下【${FriendStatusHelper.lastTagUserCount}】个联系人未备注\n\n是否继续备注")
            .setCancelable(true)
            .setNegativeButton("重新备注") { dialog, _ ->

//                TaskBySearchHelper.startTask(requireContext().applicationContext, false)

                dialog.dismiss()


            }
            .setPositiveButton("继续备注") { dialog, _ ->

                dialog.dismiss()
//                TaskBySearchHelper.startTask(requireContext().applicationContext, true)

            }.create().show()
    }

    private fun showTipDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("温馨提示")
            .setMessage("为了准确读取好友数量,好友名称(备注):\n①.不要为空白" +
                    "\n②.不要有重复" +
                    "\n③.不要包含奇奇怪怪的符号和表情\n\n否则可能会有遗漏,跟实际好友数量有些许偏差,是否开始读取")
            .setCancelable(true)
            .setNegativeButton("取消") { dialog, _ ->


                dialog.dismiss()
            }
            .setPositiveButton("读取") { dialog, _ ->
                clear()
//                TaskHelper.startGetUserTask(requireContext().applicationContext, true)

                dialog.dismiss()
            }.create().show()
    }

    private fun showTagTipDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("备注异常好友")
            .setMessage("在异常好友名称的后面,追加后缀 [ @异常 ] ,这样可以很方便的在微信里 批量操作管理(一键删除)这些异常关系的好友" +
                    "\n注:为了良好的追加备注效果,好友名称(备注):\n①.不要为空白(会搜不到)" +
                    "\n②.不要有重复(只会备注第一个)" +
                    "\n③.不要包含奇奇怪怪的符号和表情(可能会搜不到)\n\n这些因素可能会导致中断此次备注任务哦,是否要去备注异常好友" +
                    "\n如果检测过程中,停留在某个界面,别着急,软件有重试机制,10秒之内都是有可能继续执行任务的哦!故请耐心等待10秒哦"

            )
            .setCancelable(true)
            .setNegativeButton("取消") { dialog, _ ->
                //===========

                //===========
                dialog.dismiss()
            }
            .setPositiveButton("备注") { dialog, _ ->


                dialog.dismiss()
                if (FriendStatusHelper.filterNotNormalData().isEmpty()) {
                    Toast.makeText(requireContext(), "请先检测出异常好友", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                //因为 会跳过备注失败的异常好友,直到最后一个,故,不可能出现继续备注的对话框的
                val isContinueTag = FriendStatusHelper.lastTagUserCount
                if (isContinueTag != 0){
                    showContinueCheckTipDialog_Tag()
                }else{

//                    TaskBySearchHelper.startTask(requireContext().applicationContext, false)

                }



            }.create().show()
    }


    private fun showCheckFriendDialog() {
        var s = "两种方法可选择:\n①.转账法:\n模拟向好友转账(最后一步会停止,不会真的转账的,别担心,另外软件也不知道您的支付密码,绝不可能成功转账)\n②.建群法:\n创建群聊:" +
                "(有种罕见情况,对方已经把你删了,但他或许未设置加好友验证,此时,会发送一条进群邀请,但请放心,正常好友绝不会收到)" +
                "\n为了良好的甄选检测效果,好友名称(备注):\n❶.不要为空白\n❷.不要有重复" +
                "\n否则可能会有偏差" +
                "\n注:转账法,消耗时间比较长(因为是一个一个执行),建群法相对要快很多(批量操作,比转账法大约快30倍), 但两种方法,对方均不会知道哦" +
                "\n如果检测过程中,停留在某个界面,别着急,软件有重试机制,10秒之内都是有可能继续执行任务的哦!,故请耐心等待10秒哦"
        AlertDialog.Builder(requireContext())
            .setTitle("检测好友")
            .setMessage(s)
            .setCancelable(true)
            .setNegativeButton("①.转账法") { dialog, _ ->
                val isContinueCheck = FriendStatusHelper.lastCheckUser != null
                if (isContinueCheck) {
                    showContinueCheckTipDialog()
                } else {
                    clear()
//                    TaskHelper.startCheckTask(requireContext().applicationContext, false)
                }
                dialog.dismiss()
            }
            .setPositiveButton("②.建群法") { dialog, _ ->
                val isContinueCheck_Group = FriendStatusHelper.checkGroupCount
                if (isContinueCheck_Group != 0){
                    showContinueCheckTipDialog_Group()
                }else{
                    clear()
//                    TaskByGroupHelper.startTask(requireContext().applicationContext,false)
                }

                dialog.dismiss()
            }.create().show()
    }


    private fun showCustomImageDialog(activity: Activity) {
        // 加载自定义视图
        val view: View = layoutInflater.inflate(R.layout.dialog_image, null)

        // 获取ImageView并设置图片
        val imageView = view.findViewById<ImageView>(R.id.imageView)
        imageView.setImageResource(R.drawable.opentip) // 替换为实际图片资源ID

        // 创建AlertDialog Builder
        val builder = AlertDialog.Builder(activity)
        builder.setView(view)
            .setTitle("开启辅助服务")
            .setPositiveButton(
                "开启"
            ) { dialog, which ->
                dialog.dismiss()
                activity?.openAccessibilitySetting()
            }
            .setNegativeButton(
                "教程"
            ) { dialog, which ->
                dialog.dismiss()
                val intenti2 = Intent()
                intenti2.setAction("android.intent.action.VIEW")
                val content_url2 = Uri.parse("https://mp.weixin.qq.com/s/z-pDLUqH6EzbfFS6O7HaoA")
                intenti2.setData(content_url2)
                startActivity(intenti2)


            }


        // 显示对话框
        val alertDialog = builder.create()
        alertDialog.show()
    }




    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

}