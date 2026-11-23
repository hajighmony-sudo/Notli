# معماری نهایی Notli: Rust Music Core + Android UI

## زبان‌ها و مسئولیت‌ها

- **Rust:** هستهٔ اصلی و منبع حقیقت منطق موسیقی؛ وضعیت پخش، صف، انتخاب آهنگ، ترتیب Shuffle، Repeat، فرمان‌ها، رویدادها، Snapshot و state دانلودها.
- **Kotlin:** لایهٔ Android و اتصال سیستم‌عامل؛ JNI، سرویس پخش، Audio Focus، MediaSession، مجوزها، MediaStore، دسترسی فایل، شبکه و مدیریت چرخهٔ عمر.
- **Jetpack Compose:** فناوری رابط کاربری Android با Kotlin؛ صفحات و اجزای جدید باید Compose باشند. View/XMLهای موجود تا زمان جایگزینی و تست هر صفحه حفظ می‌شوند.
- **Media3 / ExoPlayer:** موتور decode و خروجی صدا؛ تصمیم انتخاب و جابه‌جایی آهنگ را Rust می‌گیرد و آداپتر Kotlin فقط فرمان Backend را اجرا و callback را به Rust برمی‌گرداند.

## محدودهٔ محصول

این پروژه فقط روی موسیقی متمرکز می‌ماند:
1. اسکن و دریافت فهرست آهنگ‌های دستگاه از MediaStore و پوشه‌های مجاز
2. کتابخانهٔ آهنگ‌ها، هنرمندها، آلبوم‌ها، پوشه‌ها و جست‌وجوی موسیقی
3. پخش، مکث، ادامه، توقف، Seek، قبلی و بعدی
4. صف پخش، Play Next، افزودن، حذف و جابه‌جایی آهنگ‌ها
5. Shuffle، Repeat، gapless playback و بازیابی وضعیت پخش
6. دانلود مستقیم فایل صوتی از URL مجاز، صف دانلود، توقف/ادامه و پیشرفت
7. مدیریت خطاهای فایل، شبکه، codec و منبع از دسترس خارج‌شده
8. رابط Now Playing، صف، کتابخانه، دانلودها و تنظیمات پخش
9. MediaSession، کنترل هدست/بلوتوث، اعلان و کنترل صفحهٔ قفل
10. ReplayGain، equalizer integration و قابلیت‌های پردازش صوتی فعلی، بدون حذف سازگاری موجود

قابلیت‌های نامرتبط پروژهٔ مرجع NAVAQ وارد برنامه نمی‌شوند.

## مرز Rust و Android

Rust نباید به Android API وابسته باشد. ورودی آن Trackهای نرمال‌شده و فرمان‌های نوع‌دار است. خروجی آن BackendAction و PlayerEvent است. آداپتر Android شناسهٔ پایدار Track و URI را به مدل Rust تبدیل می‌کند.

Android مسئول اجرای Media3، دسترسی واقعی به فایل/شبکه و مجوزهاست. دانلود manager در Rust state و transition را کنترل می‌کند؛ آداپتر Kotlin انتقال واقعی bytes و نوشتن فایل را انجام می‌دهد و progress را به Rust گزارش می‌کند.

## JNI و قرارداد JSON

فایل `native-core/src/jni_bridge.rs` توابع JNI را فراهم می‌کند. `RustMusicCore.kt` یک wrapper باریک برای ساخت engine، ارسال PlayerCommand، ارسال BackendEvent، دریافت Snapshot و مدیریت فرمان‌های DownloadCommand است.

PlayerCommand و BackendEvent از Serde JSON استفاده می‌کنند. پاسخ JNI به شکل `{"ok":true,"value":...}` یا `{"ok":false,"error":"..."}` برمی‌گردد. مدل‌ها و قرارداد JSON باید نسخه‌بندی شوند، چون Kotlin و Rust جداگانه کامپایل می‌شوند.

هر نمونهٔ RustMusicEngine یک handle مستقل دارد و باید همراه سرویس پخش ساخته و هنگام release بسته شود. تمام فراخوانی‌های wrapper همگام‌سازی شده‌اند تا close با dispatch رقابت نکند.

## ساخت Android

برای ساخت بومی، Rust stable، Android SDK/NDK نسخهٔ تعریف‌شده در `build.gradle` و `cargo-ndk` لازم است:

```bash
cargo install cargo-ndk --locked
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
./gradlew app:packageDebug
```

Gradle پیش از `preBuild` کتابخانهٔ `libnotli_music_core.so` را برای ABIهای arm64-v8a، armeabi-v7a، x86_64 و x86 تولید می‌کند و آن‌ها را از `app/src/main/jniLibs` در APK قرار می‌دهد. Workflow جدید هم تست Rust و ساخت APK را بررسی می‌کند.

## وضعیت فعلی مهاجرت

انجام شده:
- crate مستقل Rust برای PlayerCore، صف و مدیریت وضعیت دانلود
- مدل‌های Serde برای قراردادهای فرمان/رویداد و Snapshot
- JNI bridge برای PlayerCommand، BackendEvent، PlayerSnapshot و DownloadCommand
- wrapper Kotlin با مدیریت handle و close
- Gradle task برای cross-compile و بسته‌بندی کتابخانهٔ native
- Workflow مستقل Rust + Android برای تست و ساخت APK

هنوز انجام نشده و نباید به‌عنوان تکمیل‌شده معرفی شود:
- اتصال RustMusicEngine به ExoPlaybackStateHolder واقعی و انتقال عملیات زندهٔ playback به Rust
- تبدیل امن MusicRepository/Song به Track و بازگرداندن state/events به PlaybackStateManager/ViewModel
- انتقال persistence موجود به Snapshot نسخه‌دار Rust و مهاجرت داده‌های قدیمی
- اجرای واقعی دانلود فایل از طریق Android Download adapter و هماهنگی progress با Rust
- مهاجرت کامل صفحات XML به Compose؛ در حال حاضر پروژه ترکیبی است و Compose به‌صورت تدریجی اضافه شده
- تست دستگاه واقعی برای background playback، Bluetooth/headset، process death، دانلود و codecهای مختلف

تا پایان این مراحل، موتور Kotlin فعلی حذف نمی‌شود و Rust bridge به‌تنهایی نباید به‌عنوان مسیر فعال پخش معرفی شود. هدف نهایی یک منبع حقیقت Rust است؛ نه دو موتور موازی.
