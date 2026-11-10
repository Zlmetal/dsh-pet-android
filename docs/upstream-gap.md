# App 与上游 dsh-pet 插件的功能差距

对比基准：插件 `dsh-pet@0.4.6`（`profiles/web/node_modules/dsh-pet`，路由前缀 `/dsh-pet-7340`）
本文 App 侧：`dsh-pet-android-remote` 1.0.0.18

## 一、已经在用的（App 已对齐）

| 插件能力 | 路由 | App 侧 |
|---|---|---|
| 全量状态轮询 | `GET /state` | `PetService` 主循环 1 秒轮询，读 `sections.balance / workStatus / notify` |
| 余额 | `POST /balance` | 菜单「查看余额」+ 通知栏显示 |
| 碎碎念 | `POST /whisper` | 菜单「碎碎念」 |
| 显示/隐藏 | `POST /pet-display` | 启动/停止时切换 `both` / `none` |
| 配置读取 | `GET /config` | 读 `display`、动作权重、`workStatusTexts` |
| 动作素材 | `assets/webm` | 打包进 APK 的 106 个 webm，本地 WebView 播 |
| 对话 | `POST /chat` | **改走 DSH 原生 `/api`**（能选会话、发图、发文件），比插件的单会话记忆强 |
| 通知播报 | 插件自己弹 toast | App 用系统 TTS + HIGH 通道横幅（1.0.0.18） |

## 二、差距清单

### A. 值得同步（成本低、日常有感）

1. **待办 `/todos`（GET / POST / PATCH / DELETE）**
   插件有完整待办列表，还会被 `voiceTodoEnabled` 念出来。App 一条都没有。
   做法：菜单加「待办」，浮层列条目 + 勾选，改动直接 PATCH 回插件（这样网页端和 App 共用同一份）。
   工作量：中等（一个列表页 + 3 个请求）。

2. **睡觉 `/sleep` `/wake`**
   插件有 `sleepStartTexts / sleepLoopTexts / sleepEndTexts` 三段台词和配套动画；App 的 8 个睡觉动画（`睡觉-开始/循环/起来` 等）打包了但没用上。
   做法：长时间没交互自动 `/sleep`，点一下 `/wake`；或者菜单手动开关。
   工作量：小。

3. **多端占用锁 `/lock` `/lock/release`**
   插件支持「这只桌宠正被某端占用」——网页端和 App 同时开着时，避免两边抢着改位置、抢着说话。
   App 现在完全不管锁，两边同时拖会打架。
   工作量：小（进桌宠前 lock，退出 release），**但需要先想清楚 App 常驻运行算不算「占用」**。

4. **气泡清理 `/bubble/clear`**
   网页端关掉气泡时会通知其他端一起清。App 只能等自己的定时器。
   工作量：极小。

5. **广播 `/broadcast`**
   一端说话，所有端一起显示。App 现在只读 `pets.main.say`，广播能覆盖但不如直连干脆。
   工作量：小。

### B. 想同步但成本偏高

6. **语音 `/voice/speak` `/voice/hear`**
   插件的 `voice/speak` 走的是 NAS 上的 dsh-voice（真人感强于系统 TTS），`voice/hear` 还能语音转文字。
   App 1.0.0.18 用的是本地系统 TTS（离线、零延迟、免费），音色差一截但不用网。
   做法：加设置项，二选一；选远端时 `POST /voice/speak` 拿 mp3 再播（`DshHttp` 已有 `fetchBytes`）。
   工作量：中等（要补二进制 POST + 音频播放）。

7. **换装 / 角色 `/avatar` `/character`**
   插件能换整套立绘/角色。App 的角色是编译期打进去的静态资源（`char.json` + webm），换不了。
   做法：`assets.json` 支持运行期下载新角色包到缓存目录，WebView 从缓存加载。
   工作量：大（要处理素材版本、磁盘占用、校验）。

8. **菜单事件 `/menu` `/menu/item` `/menu/click`**
   插件把菜单项下发到所有端，网页端点一下所有端都关。App 的菜单是自己画的，和网页端不同步。
   工作量：中等。

### C. 不建议同步

9. **`/pose` `/pose/report`（ComfyUI 姿势生成）**
   NAS 上要跑 ComfyUI，App 端只是消费结果。接进来收益小、依赖重。

10. **`/playback` `/anim` 服务端驱动播放**
    App 自己有一套本地播放调度（`actions.json` + webm），接服务端的 `playbackId` 反而会打架。

11. **`/reload` `/quit`**
    那是给网页端热重载用的，App 是原生进程，语义不对。

12. **`/config/meta` `/meta` `/content` 编辑器类**
    都是网页端插件设置页用的，App 不做设置页没必要接。

## 三、建议的下一步顺序

1. 待办（A1）——唯一一个「日常真会用」的功能
2. 睡觉（A2）——动画都打包了，不接白不接
3. 多端锁（A3）——用户一旦网页和 App 同时开就会撞上
4. 远端 TTS（B6）——做成开关，愿意用网的用网的
