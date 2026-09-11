# Dublyaj — 1-bosqich (skelet)

Bu — Android ilovaning birinchi bosqichi. Hozircha quyidagilar **haqiqiy va ishlaydi**:

- 3 ta asosiy ekran (Kino, Modellar, Sozlamalar) va pastki navigatsiya
- **Kino** ekranida galereyadan haqiqiy video tanlash
- **Modellar** ekranida Whisper (nutq tanish) modellarini **haqiqiy** yuklab olish,
  progress bilan, telefon xotirasida saqlanadi (qayta yuklamaydi)
- **Sozlamalar** ekranida Groq/Azure/HuggingFace kalitlarini kiritish va saqlash
- Dublyaj ekranida 7 bosqichli **DEMO** simulyatsiya (haqiqiy ishlov hali ulanmagan)

## Keyingi bosqichlar (hali qo'shilmagan)
1. Onlayn rejim: Groq (nutq→matn, tarjima) + Azure (ovoz) — haqiqiy API chaqiruvlari
2. Oflayn rejim: whisper.cpp orqali telefonda nutq tanish
3. Video/audio ishlov berish (FFmpeg)
4. Oflayn tarjima va oflayn ovoz sintezi

## APK qanday olinadi
Har bir `git push` dan keyin GitHub Actions avtomatik `.apk` yasaydi va uni
repozitoriyning **Releases** bo'limiga ("latest" nomi bilan) qo'yadi.
Shu yerdan telefon brauzeridan to'g'ridan-to'g'ri yuklab, o'rnatish mumkin.
