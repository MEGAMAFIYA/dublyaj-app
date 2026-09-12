# Dublyaj — 2-bosqich (haqiqiy ONLAYN dublyaj)

## Nima ishlaydi (haqiqiy, demo emas)
- 3 ekran + navigatsiya (Kino / Modellar / Sozlamalar)
- **Kino**: galereyadan video tanlash
- **Modellar**: Whisper modellarini haqiqiy yuklab olish (oflayn rejim uchun, hali ulanmagan)
- **Sozlamalar**: Groq / Azure kalitlarini kiritish va saqlash
- **Dublyaj ekrani**: haqiqiy 7 bosqichli quvur (pipeline):
  1. Video yuklandi
  2. Audio ajratildi — **FFmpegsiz**, faqat Android'ning o'z `MediaExtractor`/`MediaMuxer`
     API'lari orqali (video sifatiga tegmaydi)
  3. Nutq matnga aylantirildi — Groq Whisper (`whisper-large-v3`)
  4. Tarjima qilindi — Groq LLM (`openai/gpt-oss-120b`), Python backenddagi bilan bir xil,
     ishonchli raqamlash-parser bilan
  5. Subtitr tayyor (hozircha faqat belgi — `.srt` eksport keyingi bosqichda)
  6. Dublyaj ovozi — Azure Speech (`uz-UZ-SardorNeural` / `uz-UZ-MadinaNeural`), RAW PCM
  7. Video va audio birlashtirildi — asl video oqimi o'zgarishsiz, faqat audio trek almashtiriladi
- Natija video **ulashish/saqlash** tugmasi bilan chiqadi (FileProvider orqali)

## Bilib qo'yish kerak bo'lgan cheklovlar (halol ro'yxat)
1. **Spiker aniqlanmaydi** — diarizatsiya (pyannote) hali telefonda yo'q. Ovozlar
   segment tartibi bo'yicha (juft/toq) erkak/ayolga almashtiriladi, real spikerga bog'lanmaydi.
2. **Fon tovushi/musiqa saqlanmaydi** — faqat dublyaj nutqi eshitiladi, boshqa joyda sukunat.
   Asl audio bilan aralashtirish (past ovozda pastki qatlam) keyingi bosqichda qo'shiladi.
3. **Vaqtga moslashtirish (tempo-fit) yo'q** — agar tarjima matni asl gapdan uzun bo'lsa,
   ovoz keyingi repika boshlanishidan oldin oddiy kesiladi (tezlashtirilmaydi). Bu ba'zan
   gap "kesilgan" tuyulishi mumkin — Python backenddagi `atempo` mantiqi keyingi bosqichda qo'shiladi.
4. **Uzun videolar uchun xotira** — butun audio "vaqt chizig'i" RAMda saqlanadi
   (24kHz x davomiylik). ~10-15 daqiqagacha bo'lgan videolar uchun muammosiz,
   undan uzunroq (to'liq kinolar) uchun kelajakda bosqichlab ishlov berish kerak bo'ladi.
5. Oflayn rejim hali ishlamaydi (Modellar ekranidagi tarjima/TTS modellari "Tez orada").

## Keyingi bosqichlar
- Diarizatsiya (spiker aniqlash) — telefonda yoki bulutda
- Asl audio bilan aralashtirish (background mixing)
- Tempo-fit (segment vaqtiga moslash)
- Oflayn rejim: whisper.cpp + oflayn tarjima + oflayn/tizim TTS
- .srt subtitr eksport

## APK qanday olinadi
Har bir git push'dan keyin GitHub Actions avtomatik .apk yasaydi va uni
repozitoriyning **Releases** bo'limiga ("latest" nomi bilan) qo'yadi — shu yerdan
telefon brauzeridan yuklab o'rnatiladi.

## Ishlatishdan oldin
Sozlamalar bo'limida:
- **Groq API kaliti** — https://console.groq.com/keys (bepul)
- **Azure Speech kaliti** va **hudud** (masalan eastus) — https://portal.azure.com
  (Speech resursi yaratib, "Keys and Endpoint" bo'limidan olinadi)
