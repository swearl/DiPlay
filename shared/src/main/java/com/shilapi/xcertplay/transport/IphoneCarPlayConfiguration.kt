package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log

/**
 * Descriptor-based discovery of the iPhone's CarPlay USB interfaces.
 *
 * 精简移植：目标平台 Android 4.4（API 19）没有 UsbConfiguration 与 alternateSetting 概念，
 * 因此直接在设备顶层 interface 上识别 CarPlay 所需的 Apple USB Multiplexor（USBMUX）
 * 以及 NCM/Ethernet 功能；"找到完整功能组合"即等价于原来的"找到 CarPlay configuration"。
 */
object IphoneCarPlayConfiguration {
    const val TAG = "xcertplay-usb"

    private const val USBMUX_CLASS = 0xff
    private const val USBMUX_SUBCLASS = 0xfe
    private const val USBMUX_PROTOCOL = 0x02
    private const val APPLE_ETHERNET_CLASS = 0xff
    private const val APPLE_ETHERNET_SUBCLASS = 0xfd
    private const val APPLE_ETHERNET_PROTOCOL = 0x01
    private const val NCM_CONTROL_CLASS = 0x02
    private const val NCM_CONTROL_SUBCLASS = 0x0d
    private const val PREFERRED_USBMUX_OUT = 0x04
    private const val PREFERRED_USBMUX_IN = 0x85

    /**
     * 当设备顶层 interface 上出现 USBMUX + NCM 功能组合（即原 CarPlay configuration）时
     * 返回该设备本身，否则返回 null。
     */
    fun find(device: UsbDevice): UsbDevice? {
        val usbMux = usbMuxInterface(device)
        val hasNcm = hasCdcNcm(device)
        val found = usbMux != null && hasNcm
        Log.i(
            TAG,
            "carplay interfaces found=$found usbMux=${usbMux != null} ncm=$hasNcm " +
                "appleEthernet=${hasAppleEthernet(device)} detail=${if (found) describe(device) else "none"}",
        )
        return if (found) device else null
    }

    /** Only CarPlay mode adds Apple Ethernet; iOS 17+ offers USBMUX and NCM without it (#634). */
    fun isCarPlayMode(device: UsbDevice): Boolean =
        usbMuxInterface(device) != null && hasCdcNcm(device) && hasAppleEthernet(device)

    fun describe(device: UsbDevice): String =
        // 精简移植：API 19 无 alternateSetting，描述里只保留接口编号与 class/subclass/protocol
        (0 until device.interfaceCount).joinToString(",") { index ->
            val usbInterface = device.getInterface(index)
            "${usbInterface.id}" +
                ":${usbInterface.interfaceClass.toString(16)}" +
                ".${usbInterface.interfaceSubclass.toString(16)}" +
                ".${usbInterface.interfaceProtocol.toString(16)}" +
                "x${usbInterface.endpointCount}"
        }

    fun usbMuxInterface(device: UsbDevice): UsbInterface? =
        (0 until device.interfaceCount).map(device::getInterface).firstOrNull {
            it.interfaceClass == USBMUX_CLASS &&
                it.interfaceSubclass == USBMUX_SUBCLASS &&
                it.interfaceProtocol == USBMUX_PROTOCOL
        }

    fun usbMuxEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        val endpoints = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
        val out = endpoints.firstOrNull {
            it.address == PREFERRED_USBMUX_OUT &&
                it.direction == UsbConstants.USB_DIR_OUT &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        } ?: endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_OUT &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        val input = endpoints.firstOrNull {
            it.address == PREFERRED_USBMUX_IN &&
                it.direction == UsbConstants.USB_DIR_IN &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        } ?: endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_IN &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        return if (out != null && input != null) out to input else null
    }

    private fun hasCdcNcm(device: UsbDevice): Boolean =
        (0 until device.interfaceCount).map(device::getInterface).any {
            it.interfaceClass == NCM_CONTROL_CLASS && it.interfaceSubclass == NCM_CONTROL_SUBCLASS
        }

    private fun hasAppleEthernet(device: UsbDevice): Boolean =
        (0 until device.interfaceCount).map(device::getInterface).any {
            it.interfaceClass == APPLE_ETHERNET_CLASS &&
                it.interfaceSubclass == APPLE_ETHERNET_SUBCLASS &&
                it.interfaceProtocol == APPLE_ETHERNET_PROTOCOL
        }
}
