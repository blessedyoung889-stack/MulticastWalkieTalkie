package com.multicast.walkietalkie

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.multicast.walkietalkie.databinding.ItemDeviceBinding

class DeviceAdapter : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    private var items: List<DeviceInfo> = emptyList()

    fun submitList(newItems: List<DeviceInfo>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class DeviceViewHolder(private val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(device: DeviceInfo) {
            val now = System.currentTimeMillis()
            binding.textDeviceName.text = device.name
            if (device.isSpeaking(now)) {
                binding.textDeviceState.text = "\uD83D\uDD0A speaking"
                binding.dotStatus.background.setTint(Color.parseColor("#FFC107"))
            } else {
                binding.textDeviceState.text = ""
                binding.dotStatus.background.setTint(Color.parseColor("#4CAF50"))
            }
        }
    }
}
