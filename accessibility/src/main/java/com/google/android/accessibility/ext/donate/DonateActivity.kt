package com.google.android.accessibility.ext.donate

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.android.accessibility.ext.R
import com.android.accessibility.ext.databinding.ZhiActivityXpqBinding
import com.google.android.accessibility.ext.activity.XpqBaseActivity
import com.google.android.accessibility.ext.utils.MMKVConst.EXTRA_KEY_PAY_CONFIG

/**
 * Created by qiang on 2025/8/9.
 */
class DonateActivity : XpqBaseActivity<ZhiActivityXpqBinding>(
    bindingInflater = ZhiActivityXpqBinding::inflate
), View.OnClickListener {
    private lateinit var binding: ZhiActivityXpqBinding

    private var mTitleTv: TextView? = null
    private var mSummeryTv: TextView? = null
    private var mTip: TextView? = null
    private var mZhiBtn: TextView? = null
    private val ZHI_WAY_WECHAT = 0 //weixin
    private var mZhiWay = 0
    private var mQaView: ViewGroup? = null
    private var mZhiBg: ViewGroup? = null
    private var mQaImage: ImageView? = null

    /*******config***********/
    private var wechatTip: String? = null
    private var aliTip: String? = null
    private var wechatSao: String? = null
    private var aliSao: String? = null

    @DrawableRes
    private var wechatQaImage = 0

    @DrawableRes
    private var aliQaImage = 0
    private var aliZhiKey: String? = null //支付宝支付码，可从支付二维码中获取

    /*******config***********/

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ZhiActivityXpqBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initView()
        initData()
    }

    override fun initView_Xpq() {

    }

    override fun initData_Xpq() {

    }

    private fun initView() {
        mTitleTv = binding.zhiTitle
        mSummeryTv = binding.zhiSummery
        mZhiBtn = binding.zhiBtn
        mQaView = binding.qaLayout
        mZhiBg = binding.zhiBg
        mQaImage = binding.qaImageView
        mTip = binding.tip
        mZhiBg!!.setOnClickListener(this)
    }

    private fun initData() {
        val donateConfig = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_KEY_PAY_CONFIG, DonateConfig::class.java)
        } else {
            intent.getSerializableExtra(EXTRA_KEY_PAY_CONFIG) as? DonateConfig
        }
        if (donateConfig == null)return

        this.wechatQaImage = donateConfig.wechatQaImage
        this.aliQaImage = donateConfig.aliQaImage
        this.wechatTip = donateConfig.wechatTip
        this.aliTip = donateConfig.aliTip
        this.wechatSao = donateConfig.wechatSao
        this.aliSao = donateConfig.aliSao
        this.aliZhiKey = donateConfig.aliZhiKey

        if (!checkLegal()) {
            throw IllegalStateException("Config Erro!!!")
        } else {
            if (TextUtils.isEmpty(wechatTip)) wechatTip = getString(R.string.wei_zhi_tip)
            if (TextUtils.isEmpty(aliTip)) aliTip = getString(R.string.ali_zhi_tip)
            if (TextUtils.isEmpty(wechatSao)) wechatSao = getString(R.string.wei_zhi_sao)
            if (TextUtils.isEmpty(aliSao)) aliSao = getString(R.string.ali_zhi_sao)

            mZhiBg!!.setBackgroundResource(R.drawable.common_bg_xpq)
            mTitleTv!!.setText(R.string.wei_zhi_title)
            mSummeryTv!!.text = wechatTip
            mZhiBtn!!.text = wechatSao
            mQaImage!!.setImageResource(wechatQaImage)
        }

        val animator = ObjectAnimator.ofFloat(mTip, "alpha", 0f, 0.66f, 1.0f, 0f)
        animator.duration = 2888
        animator.repeatCount = 6
        animator.interpolator = AccelerateDecelerateInterpolator()
        animator.repeatMode = ValueAnimator.REVERSE
        // 添加完整的动画监听器
        animator.addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationStart(animation: android.animation.Animator) {
                // 动画开始时的处理
            }

            override fun onAnimationEnd(animation: android.animation.Animator) {
                // 动画结束后重置透明度为1（完全不透明）
                mTip!!.alpha = 1.0f
            }

            override fun onAnimationCancel(animation: android.animation.Animator) {
                // 动画被取消时的处理
            }

            override fun onAnimationRepeat(animation: android.animation.Animator) {
                // 动画重复时的处理
            }
        })
        animator.start()
    }

    private fun checkLegal(): Boolean {
        if (wechatQaImage == 0 || aliQaImage == 0 || TextUtils.isEmpty(aliZhiKey)) {
            return false
        }
        return true
    }

    override fun onClick(v: View) {
        if (v.id == R.id.zhi_btn) {
            if (mZhiWay == ZHI_WAY_WECHAT) {
                // 在Activity中调用
                val targetView = binding.zhiBg
                val fileName = "screenshot_" + System.currentTimeMillis() + ".png"
                ScreenshotHelper.captureAndSaveToGallery(this, targetView, fileName)
                WeXin.startWeZhi(this)
            } else {
                ZhiFuBao.startAlipayClient(this, aliZhiKey!!)
            }
        } else if (v == mZhiBg) {
            if (mZhiWay == ZHI_WAY_WECHAT) {
                mZhiBg!!.setBackgroundResource(R.color.common_blue)
                mTitleTv!!.setText(R.string.ali_zhi_title)
                mSummeryTv!!.text = aliTip
                mZhiBtn!!.text = aliSao
                mQaImage!!.setImageResource(aliQaImage)
            } else {
                mZhiBg!!.setBackgroundResource(R.drawable.common_bg_xpq)
                mTitleTv!!.setText(R.string.wei_zhi_title)
                mSummeryTv!!.text = wechatTip
                mZhiBtn!!.text = wechatSao
                mQaImage!!.setImageResource(wechatQaImage)
            }
            mZhiWay = ++mZhiWay % 2
        }
    }

}