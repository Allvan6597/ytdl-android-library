# YTDLAndroid

مكتبة Kotlin Android لاستخراج وتحميل فيديوهات YouTube، مبنية على نفس منطق **yt-dlp 2026.06.09**.

---

## كيف تعمل؟ — المنطق المستنسخ من yt-dlp

```
yt-dlp                          YTDLAndroid
──────────────────────────────────────────────────────
YoutubeIE._real_extract()  →   YouTubeExtractor.extract()
_INNERTUBE_CLIENTS dict    →   InnerTubeClient enum
_call_api() / POST /player →   InnerTubeService.fetchPlayerResponse()
streamingData.formats      →   parseFormat() → StreamFormat
adaptiveFormats            →   isAdaptive = true
FileDownloader (http.py)   →   StreamDownloader (Range headers + chunking)
n-parameter transform      →   NParameterUtils (عميل ANDROID لا يحتاجها)
```

### الخطوات الداخلية (مثل yt-dlp بالضبط)

```
1. استقبال URL/videoId
2. اختيار InnerTube client (ANDROID افتراضياً)
3. POST → https://www.youtube.com/youtubei/v1/player
   body: { videoId, context: { client: {...} }, contentCheckOk: true }
4. التحقق من playabilityStatus == "OK"
5. استخراج streamingData.formats (مدمجة)
6. استخراج streamingData.adaptiveFormats (DASH)
7. تحليل كل format: mimeType, codecs, url, bitrate...
8. إرجاع VideoInfo مع كل الـ formats
9. تحميل بـ Range headers مجزأ (10MB chunks)
```

---

## لماذا ANDROID client؟

| العميل | Signature Cipher | PO Token | Stream URL |
|--------|-----------------|---------|------------|
| **ANDROID** | ❌ لا يحتاج | ❌ لا يحتاج | ✅ مباشر |
| IOS | ❌ لا يحتاج | ❌ لا يحتاج | ✅ مباشر |
| WEB | ✅ يحتاج JS | ✅ يحتاج | ✅ بعد فك التشفير |

yt-dlp يُفضّل ANDROID كأول خيار لنفس السبب.

---

## الإضافة للمشروع

### build.gradle.kts
```kotlin
dependencies {
    implementation("com.ytdl:ytdl-android:1.0.0")

    // dependencies مطلوبة
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
```

### AndroidManifest.xml
```xml
<uses-permission android:name="android.permission.INTERNET" />
```

---

## الاستخدام

### 1. إنشاء instance

```kotlin
val ytdl = YTDL.Builder()
    .preferClient(InnerTubeClient.ANDROID)  // موصى به
    .enableLogging(BuildConfig.DEBUG)
    .build()
```

### 2. استخراج معلومات الفيديو

```kotlin
lifecycleScope.launch {
    val result = withContext(Dispatchers.IO) {
        ytdl.extract("https://youtube.com/watch?v=dQw4w9WgXcQ")
    }

    result.onSuccess { info ->
        println(info.title)           // عنوان الفيديو
        println(info.channelName)     // اسم القناة
        println(info.durationSeconds) // المدة
        println(info.thumbnailUrl)    // الصورة المصغرة

        // أفضل جودة مدمجة
        val best = info.bestVideo()
        println("أفضل جودة: ${best?.qualityLabel()}")

        // جميع الجودات
        info.videoFormats().forEach {
            println("${it.qualityLabel()} — ${it.url}")
        }
    }
}
```

### 3. الحصول على stream URL للتشغيل

```kotlin
// مباشر في ExoPlayer أو أي مشغل
val streamResult = ytdl.getStreamUrl(url, preferAdaptive = false)

streamResult.onSuccess { stream ->
    val mediaItem = MediaItem.fromUri(stream.videoStreamUrl)
    exoPlayer.setMediaItem(mediaItem)
    exoPlayer.prepare()
    exoPlayer.play()
}
```

### 4. تحميل الفيديو

```kotlin
lifecycleScope.launch {
    val info = ytdl.extract(url).getOrThrow()
    val destDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!

    ytdl.download(info, destDir)
        .flowOn(Dispatchers.IO)
        .collect { result ->
            when (result) {
                is DownloadResult.Progress ->
                    progressBar.progress = result.percentage.toInt()

                is DownloadResult.Success ->
                    Toast.makeText(ctx, "تم: ${result.filePath}", Toast.LENGTH_SHORT).show()

                is DownloadResult.Error ->
                    Toast.makeText(ctx, result.message, Toast.LENGTH_LONG).show()
            }
        }
}
```

### 5. تحميل DASH (أعلى جودة)

```kotlin
val info = ytdl.extract(url).getOrThrow()
val (videoFlow, audioFlow) = ytdl.downloadDash(info, destDir) ?: return

// تحميل الملفين بالتوازي
launch { videoFlow.collect { /* progress video */ } }
launch { audioFlow.collect { /* progress audio */ } }
// ثم دمجهما بـ MediaMuxer أو mp4parser
```

---

## هيكل المشروع

```
library/src/main/kotlin/com/ytdl/android/
├── YTDL.kt                        ← Public API — entry point
├── model/
│   ├── VideoInfo.kt               ← VideoInfo, StreamFormat, DownloadResult
│   ├── InnerTubeClient.kt         ← ANDROID, IOS, WEB, TV_EMBEDDED configs
│   └── YTDLConfig.kt              ← إعدادات المكتبة
├── core/
│   └── InnerTubeService.kt        ← HTTP POST إلى /youtubei/v1/player
├── extractor/
│   └── YouTubeExtractor.kt        ← تحليل player response + fallback chain
├── downloader/
│   └── StreamDownloader.kt        ← تحميل مجزّأ مع Range headers
└── utils/
    ├── YouTubeUrlUtils.kt          ← استخراج video ID من أي رابط
    └── NParameterUtils.kt          ← n-parameter (nsig) للـ WEB client
```

---

## القيود الحالية

| الميزة | الوضع | الحل |
|--------|-------|------|
| WEB client signature decryption | غير مكتمل | ANDROID client كافٍ في أغلب الحالات |
| n-parameter JS transform | يحتاج QuickJS JNI | غير مطلوب مع ANDROID client |
| PO Token | غير مدعوم | ANDROID لا يحتاجه |
| دمج DASH (video+audio) | خارج المكتبة | استخدم MediaMuxer أو mp4parser |
| playlist | قريباً | طلب /browse API |

---

## الإذن القانوني

هذه المكتبة مبنية على **reverse engineering** لـ InnerTube API العام.
- yt-dlp مرخص بـ **The Unlicense** (public domain)
- استخدام لأغراض شخصية فقط
- لا تنتهك شروط خدمة YouTube التجارية

---

*مرجع: yt-dlp 2026.06.09 — `yt_dlp/extractor/youtube/_video.py`*
