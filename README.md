# 衣橱app

本地优先的 Android 衣橱应用，手机应用名称为“小衣橱”。支持照片录入、离线抠图、自由搭配和备份恢复。

当前版本 **0.15.1**（versionCode 16），支持 **Android 8.0+**。

## 功能

- 拍照 / 相册单张或批量录入，支持分类、搜索、属性筛选、排序和收藏。
- 离线自动抠图、裁剪、手动擦除 / 恢复、撤销 / 重做及局部放大镜。
- 自由搭配画布，支持移动、缩放、旋转、图层、裁剪、滤镜和可编辑保存。
- 搭配图片导出、穿搭日历、合集和本地推荐。
- 回收站、备份提醒、完整备份 / 合并恢复及重复导入保护。
- 搜索 / 筛选快捷重置。

核心功能无需账号或付费接口，照片保存在应用私有空间。卸载前请导出备份。

## 下载

安装包见 [v0.15.1 Release](https://github.com/r1377171249/yichu-app/releases/tag/v0.15.1)。

升级前建议导出备份，并使用相同签名的 APK 覆盖安装。自行构建或 CI 生成的调试包签名可能不同。

APK SHA-256：

```text
d2257a6e7282d39dbc82d0dbbcb8cdbe7c571b9f92753e288482eace8c795737
```

## 构建

使用 JDK 21、Android SDK Platform 36 和 Build Tools 36.0.0。在 Android Studio 中打开项目，或执行：

```sh
./gradlew assembleDebug testDebugUnitTest
```

Windows 使用 `gradlew.bat`。APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

技术栈：Kotlin、Jetpack Compose、Room、ONNX Runtime、U²-Net-p。自动抠图为通用前景分割，可按需手动修补。

## 更新与许可

[更新日志](CHANGELOG.md) · [贡献说明](CONTRIBUTING.md) · [第三方声明](third_party/NOTICE.md)

原创代码采用 [MIT](LICENSE)；第三方模型与依赖保留各自许可。
