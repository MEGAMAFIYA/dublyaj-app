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
1. **Spiker aniqlanmaydi (haqiqiy ma'noda)** — real diarizatsiya (pyannote) hali
   telefonda yo'q. Buning o'rniga PITCH (ovoz balandligi) asosidagi yengil usul
   ishlatiladi: har bir segmentning o'rtacha F0'si hisoblanadi va past/baland
   pitch bo'yicha ikki guruhga (erkak/ayol ehtimoli) ajratiladi. Bu 2-3 xil
   bir jinsdagi odamni farqlamaydi, lekin erkak/ayol almashinuvini yaxshi ushlaydi.
2. **Fon tovushi endi past balandlikda saqlanadi** (~22%) — musiqa/shovqin butunlay
   o'chirilmaydi, dublyaj nutqi ustida eshitiladi.
3. **Vaqtga qisman moslashtirish (tempo-fit)** — tarjima matni asl gapdan uzun bo'lsa,
   ovoz avval tezlashtirib "sig'dirishga" harakat qilinadi (soddalashtirilgan usul —
   pitch biroz o'zgaradi, professional WSOLA emas), faqat shundan keyin ham
   sig'masa qolgan qismi kesiladi.
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
