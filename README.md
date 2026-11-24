# Mobile OBS（Android）

手機版 OBS 風格：多路影像來源、子母畫面、字幕、圖片/背景、混音、MP4 錄影、RTMP 直播。

## 建置
1. 用 **Android Studio（Koala 以上）** 開啟本資料夾，等 Gradle 同步（會自動補 Gradle Wrapper）。
2. 接上 Android 8.0（API 26）以上的手機，按 Run。
3. 允許「相機」「麥克風」權限。
4. 錄影檔：`Android/data/com.example.mobileobs/files/Movies/`

## 檔案
| 檔案 | 作用 |
|---|---|
| `GlCompositor.kt` | OpenGL 合成：圖層(位置/大小/旋轉/翻轉/裁切)，同時輸出到預覽、錄影、直播 |
| `CameraSource.kt` | Camera2 來源（前/後鏡頭、系統提供的外接相機） |
| `UvcSource.kt` | USB 採集卡 UVC 直連（UVCAndroid 函式庫，專案中唯一用到它的檔案） |
| `AudioMixer.kt` | 兩個音源通道、音量/靜音/音量表、混音；可同時餵給錄影與直播 |
| `Recorder.kt` | H.264/H.265 + AAC → MP4 |
| `Streamer.kt` | H.264 + AAC → RTMP（與錄影各自獨立編碼，可不同解析度/碼率，可同時進行） |
| `RtmpClient.kt` | 自製 RTMP 推流（rtmp / rtmps），網路太慢時丟影格而不是卡住 |
| `MainActivity.kt` | 介面與手勢 |

## 直播設定
- **YouTube**：伺服器 `rtmp://a.rtmp.youtube.com/live2`，金鑰貼 YouTube Studio 的串流金鑰。
- **Twitch**：`rtmp://live.twitch.tv/app`，金鑰為你的 Stream Key。
- **Facebook**：`rtmps://live-api-s.facebook.com:443/rtmp/`。
- 直播固定 H.264；建議 720p / 30fps / 2.5 Mbps 起步。
- 金鑰不會被儲存（只記住伺服器網址）。

## 圖層旋轉 / 裁切
點選預覽上的圖層後，「選取圖層」區可 ↻旋轉90°、⇋水平翻轉、四邊各裁切 0–45%。
圖片、字幕、攝影機畫面都適用。

## HDMI 採集卡（兩種連法）
1. 先看影像來源清單有沒有「外接相機(系統提供)」，有就直接用，最穩。
2. 否則選「USB 採集卡直連 (UVC)」，第一次會跳出 USB 權限視窗。
採集卡的聲音通常會顯示為 USB 音源，到「聲音來源」選單選它。
同時接多張採集卡受 USB 頻寬限制，建議每路 720p。

## 尚未驗證 / 已知限制
- 本專案未實際編譯與實機測試。RTMP 為自製實作，請先用 `nginx-rtmp` 或 YouTube 的「測試串流」驗證。
- `UvcSource.kt` 依 UVCAndroid 的 API 撰寫；若版本不同導致編譯錯誤，只需修改該檔案。
  若不需要 UVC：刪除該檔、build.gradle.kts 的 UVCAndroid 那一行，以及 MainActivity 中兩處 UvcSource 用法。
- 畫面鎖定橫向；圖片未處理 EXIF 旋轉。
- 旋轉僅支援 90° 倍數。

## 不裝 Android Studio，直接取得 APK（GitHub Actions）
1. 在 GitHub 建立一個新的空 repo，把本資料夾所有檔案（含隱藏的 `.github`）上傳到 main 分支。
2. 到 repo 的 **Actions** 分頁，等 "Build APK" 跑完（約 5–10 分鐘）；也可按 "Run workflow" 手動觸發。
3. 點進該次執行，在最下方 **Artifacts** 下載 `MobileObs-debug-apk`，解壓得到 `app-debug.apk`。
4. 傳到手機，開啟「允許安裝未知來源 App」後安裝。
5. 若建置失敗，把 Actions 的錯誤訊息貼給 Claude，就能針對性修正。
