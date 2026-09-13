package uz.dublyaj.app.data.pipeline

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uz.dublyaj.app.data.audio.AudioTools
import uz.dublyaj.app.data.audio.PitchAnalyzer
import uz.dublyaj.app.data.model.TranscriptSegment
import uz.dublyaj.app.data.network.AzureTtsClient
import uz.dublyaj.app.data.network.GroqClient
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class PipelineStep(val index: Int, val label: String) {
    VIDEO_LOADED(0, "1/7 Video yuklandi"),
    AUDIO_EXTRACTED(1, "2/7 Audio ajratildi"),
    TRANSCRIBED(2, "3/7 Nutq matnga aylantirildi"),
    TRANSLATED(3, "4/7 Tarjima qilindi"),
    SUBTITLES_READY(4, "5/7 Subtitr tayyor"),
    DUB_AUDIO_READY(5, "6/7 Dublyaj ovozi yaratildi"),
    MUXED(6, "7/7 Video va audio birlashtirildi")
}

/**
 * ONLAYN rejim uchun to'liq dublyaj quvuri (pipeline).
 *
 * Spiker aniqlash haqida: haqiqiy pyannote-darajasidagi diarizatsiya
 * telefonda serversiz ishlashi uchun juda og'ir (katta model + murakkab
 * pipeline). Shu sabab bu yerda PITCH (ovoz balandligi) asosidagi yengil
 * usul qo'llaniladi — PitchAnalyzer'ga qarang: har bir segmentning o'rtacha
 * F0'si hisoblanadi va past/baland pitch bo'yicha ikki guruhga (erkak/ayol
 * ehtimoli) ajratiladi. Bu 2-3 xil bir jinsdagi odamni farqlamaydi, lekin
 * erkak/ayol almashinuvini yaxshi ushlaydi.
 *
 * Fon ovozi va vaqtga moslashtirish haqida: asl audio (musiqa/fon shovqin)
 * endi past balandlikda dublyaj ustiga aralashtiriladi (butunlay
 * o'chirilmaydi). Tarjima matni asl gapdan uzunroq bo'lib, keyingi segment
 * vaqtiga "bosib" ketishi mumkin bo'lsa, ovoz oddiy kesilmasdan avval
 * TEZLASHTIRILADI (soddalashtirilgan usul — pitch biroz o'zgaradi, lekin
 * so'z "kesilib qolishi"dan ko'ra tabiiyroq) va faqat shundan keyin ham
 * sig'masa, qolgan qismi kesiladi.
 */
class DubbingPipeline(
    private val context: Context,
    groqApiKey: String,
    azureApiKey: String,
    azureRegion: String
) {
    private val groq = GroqClient(groqApiKey)
    private val azure = AzureTtsClient(azureApiKey, azureRegion)

    private val workDir: File by lazy {
        File(context.cacheDir, "dublyaj_work").apply { mkdirs() }
    }

    // Fon ovozi qancha balandlikda eshitilishi (0.0 = butunlay o'chirilgan,
    // 1.0 = asl balandlik). Past qiymat — dublyaj nutqi aniq eshitilishi uchun.
    private val backgroundVolume = 0.22

    // Tarjima segmenti o'z vaqt oralig'idan qanchagacha tezlashtirib
    // "sig'dirilishi" mumkinligi chegarasi (Python backenddagi MAX_TEMPO bilan
    // bir xil mantiq — undan ortig'i tabiiy ovozni buzib yuboradi).
    private val maxSpeedFactor = 1.6

    suspend fun run(videoFile: File, onStep: (PipelineStep) -> Unit): File = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || videoFile.length() < 100_000L) {
            throw Exception(
                "Video fayl topilmadi yoki juda kichik (${if (videoFile.exists()) videoFile.length() else 0} bayt). " +
                    "Iltimos, Kino bo'limiga qaytib, videoni qaytadan tanlang."
            )
        }
        val videoPath = videoFile.absolutePath
        onStep(PipelineStep.VIDEO_LOADED)

        val extractedAudio = File(workDir, "extracted_audio.m4a")
        val preparedAudio = AudioTools.prepareAudio(videoPath, extractedAudio)
        onStep(PipelineStep.AUDIO_EXTRACTED)

        val transcript = groq.transcribe(extractedAudio)
        if (transcript.isEmpty()) throw Exception("Nutq topilmadi yoki tanib bo'lmadi")
        onStep(PipelineStep.TRANSCRIBED)

        val translated = groq.translateToUzbek(transcript)
        onStep(PipelineStep.TRANSLATED)

        // Hozircha alohida .srt fayli chiqarilmaydi — bu bosqich UI'dagi 7 qadamga
        // moslash uchun belgi sifatida qoldirilgan, .srt eksport keyingi bosqichda qo'shiladi.
        onStep(PipelineStep.SUBTITLES_READY)

        val voiceForIndex = assignVoicesByPitch(translated, preparedAudio.pitchAudio)

        val sampleRate = preparedAudio.backgroundSampleRate // == AzureTtsClient.SAMPLE_RATE
        val durationMs = AudioTools.getVideoDurationMs(videoPath)
        val totalSamples = ((durationMs / 1000.0) * sampleRate).toInt().coerceAtLeast(sampleRate)

        // Faqat dublyaj ovozi (fon hali qo'shilmagan) — pastda fon bilan aralashtiriladi.
        val voiceTimeline = ShortArray(totalSamples)

        for ((index, seg) in translated.withIndex()) {
            val voice = voiceForIndex[index]
            val rawPcmBytes = try {
                azure.synthesizeToPcm(seg.text, voice)
            } catch (e: Exception) {
                // Bitta segmentdagi TTS xatosi butun jarayonni to'xtatmasin —
                // shu segment sukunat holida qoladi, qolganlari davom etadi.
                ByteArray(0)
            }
            if (rawPcmBytes.isEmpty()) continue

            var pcmShorts = AudioTools.bytesToShortsLE(rawPcmBytes)

            val startSample = (seg.start * sampleRate).toInt().coerceIn(0, voiceTimeline.size)
            val nextStartSample = if (index + 1 < translated.size) {
                (translated[index + 1].start * sampleRate).toInt().coerceIn(0, voiceTimeline.size)
            } else {
                voiceTimeline.size
            }
            val availableSamples = (nextStartSample - startSample).coerceAtLeast(0)

            // Tarjima segmenti o'z vaqt oralig'idan uzun chiqsa, avval
            // tezlashtirib "sig'dirishga" harakat qilamiz.
            if (availableSamples > 0 && pcmShorts.size > availableSamples) {
                val neededFactor = pcmShorts.size.toDouble() / availableSamples.toDouble()
                val appliedFactor = neededFactor.coerceAtMost(maxSpeedFactor)
                pcmShorts = AudioTools.changeSpeed(pcmShorts, appliedFactor)
            }

            val samplesToCopy = minOf(pcmShorts.size, availableSamples)
            for (s in 0 until samplesToCopy) {
                val timelineIndex = startSample + s
                if (timelineIndex in voiceTimeline.indices) {
                    voiceTimeline[timelineIndex] = pcmShorts[s]
                }
            }
        }
        onStep(PipelineStep.DUB_AUDIO_READY)

        // Fon ovozini (asl audio, past balandlikda) dublyaj nutqi ustiga
        // aralashtiramiz — shunda musiqa/fon shovqin butunlay yo'qolib
        // ketmaydi, faqat nutq aniq eshitiladi.
        val mixedTimeline = ShortArray(totalSamples)
        val bg = preparedAudio.backgroundPcm
        for (i in 0 until totalSamples) {
            val bgSample = if (i < bg.size) (bg[i] * backgroundVolume).toInt() else 0
            val voiceSample = voiceTimeline[i].toInt()
            val mixed = (bgSample + voiceSample)
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            mixedTimeline[i] = mixed.toShort()
        }

        val pcmOut = ByteBuffer.allocate(mixedTimeline.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in mixedTimeline) pcmOut.putShort(sample)

        val dubbedAacFile = File(workDir, "dubbed_audio.m4a")
        AudioTools.encodePcmToAac(pcmOut.array(), sampleRate, dubbedAacFile)

        val outDir = context.getExternalFilesDir(null) ?: context.filesDir
        val outputFile = File(outDir, "dublyaj_${System.currentTimeMillis()}.mp4")
        val muxOk = AudioTools.muxVideoWithNewAudio(videoPath, dubbedAacFile, outputFile)
        if (!muxOk) throw Exception("Yakuniy videoni yig'ib bo'lmadi")
        onStep(PipelineStep.MUXED)

        outputFile
    }

    /**
     * Har bir segment uchun (pitch tahlili orqali) erkak yoki ayol ovozini
     * tanlaydi. Pitch aniqlanmagan (juda qisqa/sukunat) segmentlar uchun
     * eng yaqin oldingi segmentning ovozi qo'llaniladi — shunda tasodifiy
     * "chayqalish" bo'lmaydi.
     */
    private fun assignVoicesByPitch(
        segments: List<TranscriptSegment>,
        decodedAudio: AudioTools.DecodedMonoAudio
    ): List<String> {
        val pitchPerIndex = arrayOfNulls<Double>(segments.size)
        val validIndices = mutableListOf<Int>()
        val validPitches = mutableListOf<Double>()

        for ((index, seg) in segments.withIndex()) {
            val startSample = (seg.start * decodedAudio.sampleRate).toInt()
            val endSample = (seg.end * decodedAudio.sampleRate).toInt()
            val pitch = PitchAnalyzer.estimateMedianPitchHz(decodedAudio.pcm, decodedAudio.sampleRate, startSample, endSample)
            pitchPerIndex[index] = pitch
            if (pitch != null) {
                validIndices.add(index)
                validPitches.add(pitch)
            }
        }

        // Ikkitadan kam ishonchli pitch topilsa, klasterlashning ma'nosi yo'q —
        // hammasiga bitta (erkak) ovoz beriladi, chalkash natijadan ko'ra yaxshiroq.
        if (validPitches.size < 2) {
            return List(segments.size) { AzureTtsClient.VOICE_MALE }
        }

        val groups = PitchAnalyzer.clusterIntoTwoGroups(validPitches)
        val groupPerIndex = HashMap<Int, Int>()
        for (i in validIndices.indices) {
            groupPerIndex[validIndices[i]] = groups[i]
        }

        val voices = MutableList(segments.size) { AzureTtsClient.VOICE_MALE }
        var lastGroup = 0
        for (index in segments.indices) {
            val group = groupPerIndex[index] ?: lastGroup
            lastGroup = group
            voices[index] = if (group == 0) AzureTtsClient.VOICE_MALE else AzureTtsClient.VOICE_FEMALE
        }
        return voices
    }
}
