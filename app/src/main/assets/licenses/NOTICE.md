# 自动抠图所用第三方组件

核查日期：2026-10-18。组件随应用离线使用；应用不下载模型，不上传照片。

## U²-Net-p

- 原研究及代码：[xuebinqin/U-2-Net](https://github.com/xuebinqin/U-2-Net)，Xuebin Qin 等作者，Apache-2.0；许可证原文见 `licenses/U2NET-Apache-2.0.txt`。
- ONNX 权重来自 [rembg 官方发行文件](https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx)。不是自行训练的衣物模型，也不是人像分割专用模型。
- 原样内置 `app/src/main/assets/models/u2netp.onnx`，4,574,861 字节。
- SHA-256：`309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8`。
- MD5：`8e83ca70e441ab06c318d82300c84806`，与 [rembg u2netp 会话源码](https://github.com/danielgatis/rembg/blob/main/rembg/sessions/u2netp.py) 的校验值一致。应用初始化再次校验 SHA-256。
- 当前依据上游项目 Apache-2.0 许可及 rembg 的权重分发使用；保留来源和许可。若未来公开发布或替换模型，须重新核对权重、训练数据及再分发条件。

## rembg

参考其 U2NetpSession / BaseSession 的输入归一化与输出掩膜处理，独立实现 Android Bitmap / Kotlin 流程。320×320 RGB、NCHW、按最大输入像素归一化、ImageNet mean/std、首输出 min/max 归一化。Android 采用双线性缩放，与 rembg 的 Lanczos 缩放有差异。

项目：[danielgatis/rembg](https://github.com/danielgatis/rembg)，MIT；原文见 `licenses/REMBG-MIT.txt`。

## ONNX Runtime Android

`com.microsoft.onnxruntime:onnxruntime-android:1.22.0`，Microsoft，MIT。原文见 `licenses/ONNXRuntime-MIT.txt`。[官方 Android 文档](https://onnxruntime.ai/docs/get-started/with-mobile.html)。CPU 推理，两条内部计算线程，进程内共享单个会话；每次推理关闭临时 tensor / result。

以上三份许可证及本说明副本随 APK 放在 `assets/licenses/`。版本升级同步更新两处。

## 验证照片（仅文档与测试，不预置到应用衣橱）

- 悬挂白衬衫：[Womens orvis shirt white.jpg](https://commons.wikimedia.org/wiki/File:Womens_orvis_shirt_white.jpg)，Punker1999，[CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/)。示例中缩放并生成透明背景衍生图。
- 平铺衬衫：[Shirt (AM 1999.95.2).jpg](https://commons.wikimedia.org/wiki/File:Shirt_(AM_1999.95.2).jpg)，**Auckland Museum**，[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)。示例中缩放、去除背景、合成对比底色；原图有比例尺，本模型会将其保留在衣物内部。图片和机构不构成对本应用的背书。
- 补充针织衣物样本来自 Unsplash photo-1434389677669-e08b4cac3105，仅在本机 `work/` 做探索测试；未纳入分发文档、APK 或源码包。

本文件只列本轮新增的图像模型和运行库；AndroidX / Kotlin / Compose 等基础组件保留各自上游许可。
