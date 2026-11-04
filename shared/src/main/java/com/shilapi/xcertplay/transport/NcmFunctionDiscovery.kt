package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/**
 * Finds the NCM control/data interface pair among the iPhone's top-level interfaces.
 *
 * LIVI claims the control interface, claims the data interface, and selects data alternate
 * setting 1, the setting that carries the bulk endpoints. This class only reads descriptors.
 * 精简移植：API 19 没有 UsbConfiguration/alternateSetting 概念，直接在设备顶层 interface 中
 * 按 class/subclass 匹配 NCM 控制接口与带 bulk 端点的 CDC DATA 数据接口。
 */
object NcmFunctionDiscovery {
    const val CONTROL_CLASS = 0x02
    const val CONTROL_SUBCLASS = 0x0d
    const val DATA_CLASS = 0x0a
    const val APPLE_ETHERNET_CLASS = 0xff
    const val APPLE_ETHERNET_SUBCLASS = 0xfd
    const val APPLE_ETHERNET_PROTOCOL = 0x01

    data class NcmFunction(
        val control: UsbInterface,
        val data: UsbInterface,
        val statusIn: UsbEndpoint?,
        val bulkIn: UsbEndpoint,
        val bulkOut: UsbEndpoint,
    )

    fun find(device: UsbDevice): NcmFunction? {
        return findCdcNcm(device)
    }

    private fun findCdcNcm(device: UsbDevice): NcmFunction? {
        val control = interfaces(device).firstOrNull {
            it.interfaceClass == CONTROL_CLASS && it.interfaceSubclass == CONTROL_SUBCLASS
        } ?: return null
        // 精简移植：API 19 无 alternateSetting 维度，直接取第一个带 bulk 端点对的 CDC DATA 接口
        //（设备枚举出的即为当前活动设置，CarPlay 模式下它就是携带 bulk 端点的那个）。
        val data = interfaces(device)
            .firstOrNull { it.interfaceClass == DATA_CLASS && bulkEndpoints(it) != null }
            ?: return null
        val endpoints = bulkEndpoints(data) ?: return null
        val statusIn = (0 until control.endpointCount)
            .map(control::getEndpoint)
            .singleOrNull {
                it.direction == UsbConstants.USB_DIR_IN &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_INT
            }
        return NcmFunction(control, data, statusIn, endpoints.first, endpoints.second)
    }

    private fun interfaces(device: UsbDevice): List<UsbInterface> =
        (0 until device.interfaceCount).map(device::getInterface)

    private fun bulkEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        val endpoints = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
        val input = endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        val output = endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        return if (input != null && output != null) input to output else null
    }
}
