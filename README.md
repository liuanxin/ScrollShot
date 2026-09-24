# ScrollShot / 长截图

Kotlin 原生 Android 滚动长截图工具. 从当前画面开始, 自动向下缓慢滑动, 点击顶部提示条停止, 裁剪四边后保存相册.

## 入口

- 不配置 Launcher 入口, 桌面和应用抽屉没有启动图标.
- 从下拉快捷设置的「长截图」磁贴启动.
- 首次点击磁贴进入授权页, 开启「长截图」无障碍服务.
- 平时没有悬浮球. 仅截取期间显示淡蒙层和顶部停止提示条, 截取结束即消失, 两者不会进入成品.
- 不申请网络权限, 不上传截图, 不替换系统截图或导航手势.

## 当前实现

1. 快捷面板和透明入口关闭后, 锁定当前应用窗口. 滚动节点仅辅助定位, 没有节点也照常开始.
2. 开始时读取一次系统状态栏, 保存当前位置首帧及时间、电量、信号栏, 状态栏只在长图开头出现. 固定底栏延后到长图末尾, 避免重复.
3. 模拟约 0.85 秒的向上滑动, 每次约移动滚动区域的 50%.
4. 滑动结束后快速采样, 连续画面稳定即拼接并继续滑动, 动态画面最多等待约 3 秒. 根据重叠内容匹配实际位移, 只追加新内容.
5. 点击顶部提示条停止; 页面不再移动、匹配不确定、窗口变化或达到上限时结束.
6. 原图分段落盘, 裁剪预览使用缩略图, 双指直接缩放和平移, 松开一指可继续拖动. 支持 JPG 和流式 PNG, 导出尺寸按格式与设备内存限制.
7. 裁剪页使用浅灰底和蓝色操作按钮, 底部为取消、重置、保存. 右上角分享当前裁剪图, 不写入相册; 取消分享保留草稿, 选定目标后自动关闭编辑并删除草稿. 独立分享缓存延后至少 24 小时由系统任务清理, 下次打开编辑页也会清理过期缓存. 默认 JPG, 点击顶部格式名称切换 JPG/PNG. 后台实际编码, 顶部仅显示格式、尺寸和文件大小, 自动采用 KB、MB 等单位.
8. 保存到 `Pictures/ScrollShot`, 文件名为 `yyyyMMdd-HHmmss-六位随机数.jpg/png`, 通过 MediaStore 发布到相册.
9. 长按磁贴进入设置页, 可恢复上次未保存的截图.

捕获上限: 120000 像素高、1.6 亿像素总量、单次 10 分钟, 先到者停止. JPG 使用质量 85, 宽度不超过 1080, 最多 1600 万像素并按设备堆上限进一步收紧. PNG 使用无损压缩, 最多 4000 万像素. 两种格式输出高度均不超过 60000, 超出时等比例缩小, 保留完整裁剪范围. 最低 Android 14, 主要验证设备为 Pixel 9a.

## 构建

使用 JDK 21, Android SDK 36, AGP 9.4.1, Gradle 9.7.1. Kotlin 由 AGP 内置提供.

Gradle 使用项目内 ZIP, 不由 wrapper 在线下载:

```
gradle/wrapper/gradle-9.7.1-bin.zip
```

`gradle-wrapper.properties` 的配置为 `distributionUrl=./gradle-9.7.1-bin.zip`. ZIP 已加入忽略规则, 不提交到 Git. 校验值:

```
acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a
```

依赖缓存齐全时:

```sh
JAVA_HOME=/usr/lib/jvm/jdk-21.0.10+7 ./gradlew --offline :app:assembleRelease
```

本机缺少 Maven AAPT2 缓存时, 可直接使用已安装的 SDK 工具:

```sh
JAVA_HOME=/usr/lib/jvm/jdk-21.0.10+7 ./gradlew --offline \
  -Pandroid.aapt2FromMavenOverride=/home/ty/Android/Sdk/build-tools/36.1.0/aapt2 \
  :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## USB 安装

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.github.liuanxin.scrollshot/.SetupActivity
adb shell cmd statusbar add-tile io.github.liuanxin.scrollshot/.CaptureTileService
```

开启服务后, 将磁贴移至快捷设置前排. 系统设置里的应用信息仍可管理和卸载本应用.

## 验证重点

授权页内置带连续段落编号、固定标题和底栏的测试长文, 用于检查首段保留、重叠拼接、点击顶部提示条停止与裁剪保存. 真机行为以 `docs/VALIDATION.md` 记录为准.

## 限制

- 受安全保护的窗口无法截图.
- 动态广告、持续动画、页面重排或多个滚动区域可能触发暂停; 不猜测缺失内容.
- 不要求页面声明可滚动; 无滚动节点时使用当前窗口尝试向下滑动, 画面未移动则结束.
- 主体截取应用窗口, 顶部状态栏使用开始时的画面; 底部系统导航条不包含在内.
- 截取过程中点击顶部提示条停止的触摸取消时序需要逐机验证, 不宣称所有系统均不会误触.
- 超长图即使能正确保存, 相册缩放能力也取决于查看器.


截图请求间隔采用 350ms, 高于 AOSP 的 333ms 限频阈值, 避免过快请求反而产生额外重试. 参考: https://android.googlesource.com/platform/frameworks/base/+/master/services/accessibility/java/com/android/server/accessibility/AbstractAccessibilityServiceConnection.java
