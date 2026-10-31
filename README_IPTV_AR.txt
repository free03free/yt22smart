إضافة دعم IPTV و Xtream
========================
المسار: common/src/main/java/com/liskovsoft/smartyoutubetv2/common/iptv/  (Java نقية بدون اعتماد على Android)

الصيغ المدعومة
- القوائم: m3u / m3u8 / m3u_plus (tvg-id, tvg-logo, group-title, EXTGRP, EXTVLCOPT, User-Agent|Referer)، txt (اسم,رابط - اسم$رابط - مجموعة,#genre# - عدة مصادر بـ #)، pls، xspf، json
- الأرشيفات: zip، gz، tar، tar.gz/tgz (مدمجة)، و rar، 7z، bz2، xz، .Z، cpio، ar، arj عبر المكتبات commons-compress و junrar. تُكتشف الصيغة من المحتوى وليس من الامتداد، وتعمل متداخلة (rar داخل zip داخل 7z ...) وبكلمات مرور لـ 7z و rar
  ملاحظات: junrar يقرأ RAR حتى النسخة 4 (RAR5 تظهر رسالة)، و 7z يحتاج Android 7.0+، و zstd/cab غير مدعومة
- الترميز: UTF-8 / UTF-16 / windows-1256 (عربي) تلقائياً
- Xtream Codes: رابط get.php أو player_api.php أو رابط بث /live/user/pass/id أو "server|user|pass" أو رسالة "Server/Username/Password" (بالعربية أيضاً)
- الروابط داخل أي نص أو ملف: تُحلَّل تلقائياً وتُنزَّل القوائم البعيدة (m3u, txt, zip, gz, github blob, pastebin ...) بلا حد افتراضياً
- رابط m3u8: يُفحص محتواه: قائمة قنوات => تُوسَّع، بث HLS مفرد => يُضاف كقناة

الحدود: كل الحدود (عدد التنزيلات، عمق الروابط، عمق الأرشيف، عدد الملفات، حجم الملف/الإجمالي/التنزيل) الافتراضي فيها «بلا حد»، ويمكن تحديدها من الإعدادات > IPTV > إعدادات الاستيراد، مع كلمات مرور الأرشيف والمهلة وUser agent وإعادة الضبط.
تنبيه: بلا حد يعني أن ملفاً ضخماً أو أرشيفاً خبيثاً قد يستهلك ذاكرة الجهاز؛ يُلتقط نفاد الذاكرة ويُبلَّغ عنه، لكن يمكنك وضع حد من الإعدادات.
الواجهة: الإعدادات > IPTV (استيراد من رابط/نص، الحافظة، ملف، تصفح، إعدادات الاستيراد، حذف). التشغيل عبر مشغل مثبت (VLC / MX Player).

الاختبار (بدون Android): common/src/test/.../IptvEngineSelfCheck.java  (58 فحصاً، جميعها ناجحة)
