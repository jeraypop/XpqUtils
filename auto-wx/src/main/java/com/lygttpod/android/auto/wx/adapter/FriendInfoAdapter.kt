package com.lygttpod.android.auto.wx.adapter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast

import androidx.core.content.ContextCompat


import androidx.recyclerview.widget.RecyclerView
import com.lygttpod.android.auto.wx.R
import com.lygttpod.android.auto.wx.data.WxUserInfo
import com.lygttpod.android.auto.wx.databinding.ItemFriendBinding
import com.lygttpod.android.auto.wx.em.FriendStatus


class FriendInfoAdapter : RecyclerView.Adapter<FriendInfoAdapter.FriendInfoViewHolder>() {

    private var list = mutableListOf<WxUserInfo>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FriendInfoViewHolder {
        return FriendInfoViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_friend, parent, false)
        )
    }

    override fun onBindViewHolder(holder: FriendInfoViewHolder, position: Int) {
        holder.bindData(list[position], position)
    }

    override fun getItemCount() = list.size

    fun clear() {
        list.clear()
        notifyDataSetChanged()
    }

    fun filterNotNormalData() {
        this.list =
            list.filterNot { it.status == FriendStatus.NORMAL || it.status == FriendStatus.UNKNOW }
                .toMutableList()
        notifyDataSetChanged()
    }
    fun filterUnCheckData() {
        this.list =
            list.filterNot { it.status == FriendStatus.NORMAL || it.status == FriendStatus.UNKNOW }
                .toMutableList()
        notifyDataSetChanged()
    }
    fun filterAllData() {
        this.list =
            list.filterNot { it.status == FriendStatus.NORMAL || it.status == FriendStatus.UNKNOW }
                .toMutableList()
        notifyDataSetChanged()
    }

    fun setData(list: MutableList<WxUserInfo>) {
        this.list = list
        notifyDataSetChanged()
    }

    fun addData(data: WxUserInfo) {
        val index = list.indexOfFirst { it.nickName == data.nickName }
        if (index == -1) {
            this.list.add(data)
            notifyItemInserted(itemCount)
        } else {
            this.list[index] = data
            notifyItemChanged(index)
        }
    }

    fun addDatas(newData: MutableList<WxUserInfo>) {
        this.list.addAll(newData)
        notifyItemRangeInserted(list.size - newData.size, newData.size)
    }

    class FriendInfoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val binding = ItemFriendBinding.bind(view)


        init {

            itemView.setOnClickListener {
                var position = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    //  大于等于31及以上执行内容
//                    position = getAbsoluteAdapterPosition()
                    position = adapterPosition
                } else {
                    //  低于31以下执行内容
                    position = adapterPosition
                }
                val vibrator = itemView.context?.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    vibrator.vibrate(50)
                }
                val clipboard = itemView.context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("text", binding.tvNickName.text))
                Toast.makeText(itemView.context, "已复制 "+binding.tvNickName.text, Toast.LENGTH_SHORT).show()
            }

            itemView.setOnLongClickListener {
                // 长按事件的处理逻辑

                Toast.makeText(itemView.context, "长按 "+binding.tvNickName.text, Toast.LENGTH_SHORT).show()
                true // 返回true表示事件已经被消费
            }
        }


        fun bindData(wxUserInfo: WxUserInfo, position: Int) {
            binding.tvIndex.text = "${position + 1}"
            binding.tvNickName.text = wxUserInfo.nickName
            binding.tvWxCode.text = wxUserInfo.sendContent
            binding.tvWxCode.visibility =
                if (wxUserInfo.sendContent.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.tvStatus.text = wxUserInfo.status.status
            val color = when (wxUserInfo.status) {
                FriendStatus.BLACK -> R.color.friend_black
                FriendStatus.DELETE -> R.color.friend_delete
                FriendStatus.ACCOUNT_EXCEPTION -> R.color.friend_exc
                FriendStatus.NORMAL -> R.color.friend_normal
                FriendStatus.UNKNOW -> R.color.friend_unknow
            }
            binding.tvStatus.setTextColor(ContextCompat.getColor(itemView.context, color))
        }
    }
}