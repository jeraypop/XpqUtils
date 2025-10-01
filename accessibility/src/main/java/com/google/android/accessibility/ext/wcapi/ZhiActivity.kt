package com.google.android.accessibility.ext.wcapi

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.animation.AccelerateDecelerateInterpolator
import com.android.accessibility.ext.BuildConfig
import com.android.accessibility.ext.R
import com.android.accessibility.ext.databinding.ZhiActivityXpqBinding


class ZhiActivity : Activity() {
    private var mZhiWay = 0
    private val binding: ZhiActivityXpqBinding by lazy { ZhiActivityXpqBinding.inflate(layoutInflater) }
    @SuppressLint("NewApi")
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        val config = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(BuildConfig.EXTRA_KEY_PAY_CONFIG, PayConfig::class.java)
        } else {
            intent.getSerializableExtra(BuildConfig.EXTRA_KEY_PAY_CONFIG)
        }
//        val config = intent.getSerializableExtra(BuildConfig.EXTRA_KEY_PAY_CONFIG.restoreAllIllusion())
        config ?: throw IllegalStateException("Config Errol!!!")
        if (config is PayConfig) {
            binding.zhiBg.setOnClickListener {
                if (mZhiWay == 0) {
                    binding.zhiBg.setBackgroundResource(R.color.common_blue)
                    binding.zhiTitle.setText(R.string.ali_zhi_title)
                    binding.zhiSummery.text = config.aliTip ?: getString(R.string.ali_zhi_tip)
                    binding.qaImageView.setImageResource(config.aliQaImage)
                    binding.zhiBtn.text = config.aliSao ?: getString(R.string.ali_zhi_sao)
                } else {
                    binding.zhiBg.setBackgroundResource(R.drawable.common_bg_xpq)
                    binding.zhiTitle.setText(R.string.wei_zhi_title)
                    binding.zhiSummery.text = config.weChatTip ?: getString(R.string.wei_zhi_tip)
                    binding.qaImageView.setImageResource(config.weChatQaImage)
                    binding.zhiBtn.text = config.aliSao ?: getString(R.string.wei_zhi_sao)
                }
                mZhiWay = ++mZhiWay % 2
            }
            binding.zhiBtn.setOnClickListener {
                if (mZhiWay == 0) {
                    startWeZhi(binding.zhiBg)
                } else {
                    goAliPayClient(config.aliZhiKey)
                }
            }
            binding.zhiBg.setBackgroundResource(R.drawable.common_bg_xpq)
            binding.zhiTitle.setText(R.string.wei_zhi_title)
            binding.zhiSummery.text = config.weChatTip ?: getString(R.string.wei_zhi_tip)
            binding.qaImageView.setImageResource(config.weChatQaImage)
            val animator = ObjectAnimator.ofFloat(binding.tip, "alpha", 0f, 0.66f, 1.0f, 0f)
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
                    binding.tip.alpha = 1.0f
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
    }
}