# Android 使用 CameraX 快速实现仿微信短视频录制（轻触拍照、长按录像）

## 为什么要使用 CameraX

借用官方文档的描述：

- CameraX 提供一致且易用的 API 接口，适用于大多数 Android 设备，并可向后兼容至 Android 5.0。
- 采取了一种具有生命周期感知能力且基于用例的更简单方式。
- 它还解决了设备兼容性问题，因此您无需在代码库中添加设备专属代码。

简而言之就是：集成简单、兼容好，不要手动处理生命周期。

关于 CameraX 的使用请看官方文档，文档已经写的很好了，这里不在阐述了。

## 需要实现的功能

- 类似微信聊天功能栏中的"拍摄"功能
- 轻触拍照
- 长按录像
- 摄像头前后镜头切换
- 闪光灯（关闭、自动、常开）
- 录制视频时是否需要录制音频
- 视频清晰度控制（480p、1080p、2160p等）
- 代码尽可能少，耦合性低，不需要引入第三方库

## 效果展示

截图照片：

[![oLSH4e.md.jpg](https://s4.ax1x.com/2021/12/12/oLSH4e.md.jpg)](https://imgtu.com/i/oLSH4e)

GIF 效果图

[![oLpbZV.md.gif](https://s4.ax1x.com/2021/12/12/oLpbZV.md.gif)](https://imgtu.com/i/oLpbZV)

## 项目总体结构

![image.png](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/c217e196c7ce45bd928032b81aa68973~tplv-k3u1fbpfcp-watermark.image?)

代码不难，具体看 github 
> https://github.com/ldlywt/MyCameraX

## 参考链接

- [官方文档](https://developer.android.com/training/camerax?hl=zh_cn)
- [camera-samples](https://github.com/android/camera-samples)



