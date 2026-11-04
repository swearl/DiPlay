package com.shilapi.xcertplay.transport

/**
 * USB 设备标识（vendorId/productId）。
 * 精简移植：从已删除的 Ch341DeviceMatcher 中拆出，供 iPhone USB 匹配使用。
 */
data class UsbDeviceId(val vendorId: Int, val productId: Int)
