package com.google.android.accessibility.ext.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.children
import com.android.accessibility.ext.R

/**
 * Company    :
 * Author     : Lucas     联系WX:780203920
 * Date       : 2026/2/1  19:04
 * Description:This is TaichiFabMenuView
 */
data class FabMenuItem(
    val title: String,
    @DrawableRes val icon: Int,
    val onClick: () -> Unit
)

class TaichiFabMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val mainFab: ImageButton
    private val menuContainer: LinearLayout
    private val maskView: View

    private var expanded = false

    init {
        LayoutInflater.from(context)
            .inflate(R.layout.view_taichi_fab_menu_advanced, this, true)

        mainFab = findViewById(R.id.fabMain)
        menuContainer = findViewById(R.id.menuContainer)
        maskView = findViewById(R.id.maskView)

        mainFab.setOnClickListener { toggle() }

        maskView.setOnClickListener {
            collapse()
        }
    }

    companion object {
        @JvmStatic
        fun createFabMenuItem(
            title: String,
            @DrawableRes icon: Int,
            onClick: Runnable
        ): FabMenuItem {
            return FabMenuItem(title, icon) {
                onClick.run()
            }
        }
    }

    fun setMenus(items: List<FabMenuItem>) {
        menuContainer.removeAllViews()
        items.forEach { item ->
            menuContainer.addView(createMenuItem(item))
        }
    }

    private fun createMenuItem(item: FabMenuItem): View {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.item_fab_menu, menuContainer, false)

        val titleView = view.findViewById<TextView>(R.id.tvTitle)
        val iconView = view.findViewById<ImageButton>(R.id.btnIcon)
        titleView.text = item.title
        iconView.setImageResource(item.icon)
        // 图标的点击事件 - 可以执行相同的逻辑或不同的逻辑
    

        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                item.onClick()
                collapse()
            }
            true // ✅ 吃掉整个事件链，绝不穿透
        }

        iconView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                item.onClick()
                collapse()
            }
            true
        }


        // 初始状态（用于动画）
        view.alpha = 0f
        view.translationY = 30f

        return view
    }

    private fun toggle() {
        if (expanded) collapse() else expand()
    }

    private fun expand() {
        expanded = true
        maskView.visibility = VISIBLE
        menuContainer.visibility = VISIBLE

        mainFab.animate()
            .rotation(45f)
            .setDuration(200)
            .start()

        // 依次弹出动画
        menuContainer.children.forEachIndexed { index, child ->
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(index * 40L)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }


    private fun collapse() {
        if (!expanded) return
        expanded = false

        mainFab.animate()
            .rotation(0f)
            .setDuration(200)
            .start()

        // 收起动画（反向）
        menuContainer.children.forEachIndexed { index, child ->
            child.animate()
                .alpha(0f)
                .translationY(30f)
                .setStartDelay(index * 20L)
                .setDuration(150)
                .withEndAction {
                    if (index == menuContainer.childCount - 1) {
                        menuContainer.visibility = GONE
                        maskView.visibility = GONE
                    }
                }
                .start()
        }
    }
}
