package com.shilapi.xcertplay

import com.shilapi.xcertplay.compat.MiniFileProvider

/** 精简移植：改用自研 MiniFileProvider；独立的 provider 身份避免合并后的 manifest 扩大报告文件的可见路径。 */
class DiagnosticReportProvider : MiniFileProvider()
