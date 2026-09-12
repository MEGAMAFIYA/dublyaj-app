package uz.dublyaj.app.data.pipeline

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uz.dublyaj.app.data.audio.AudioTools
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
 * ONLAYN rejim uchun to'liq dublyaj quvuri (pipeline). Diqqat: bu bosqichda
 * (Phase 2) diarizatsiya (kim qachon gapirgani) hali telefonda ishlamaydi —
 * shuning uchun ovozlar segment tartibi bo'yicha (juft/toq) almashtiriladi,
 * haqiqiy spikerga bog'lab emas. Bu keyingi bosqichda yaxshilanadi.
 *
 * Shuningdek, asl videoning fon tovushi/musiqasi saqlanmaydi — faqat
 * dublyaj qilingan nutq eshitiladi (boshqa joylarda sukunat). Original
 * audio bilan aralashtirish (mixing) keyingi bosqichda qo'shiladi.
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

    suspend fun run(videoFile: File, onStep: (PipelineStep) -> Unit): File = withContext(Dispatchers.IO) {
        val videoPath = videoFile.absolutePath
        onStep(PipelineStep.VIDEO_LOADED)

        val extractedAudio = File(workDir, "extracted_audio.wav")
        AudioTools.decodeAudioTrackToWav(videoPath, extractedAudio)
        onStep(PipelineStep.AUDIO_EXTRACTED)

        val transcript = groq.transcribe(extractedAudio)
        if (transcript.isEmpty()) throw Exception("Nutq topilmadi yoki tanib bo'lmadi")
        onStep(PipelineStep.TRANSCRIBED)

        val translated = groq.translateToUzbek(transcript)
        onStep(PipelineStep.TRANSLATED)

        // Hozircha alohida .srt fayli chiqarilmaydi — bu bosqich UI'dagi 7 qadamga
        // moslash uchun belgi sifatida qoldirilgan, .srt eksport keyingi bosqichda qo'shiladi.
        onStep(PipelineStep.SUBTITLES_READY)

        val sampleRate = AzureTtsClient.SAMPLE_RATE
        val durationMs = AudioTools.getVideoDurationMs(videoPath)
        val totalSamples = ((durationMs / 1000.0) * sampleRate).toInt().coerceAtLeast(sampleRate)
        val timeline = ShortArray(totalSamples) // 0 = sukunat

        for ((index, seg) in translated.withIndex()) {
            val voice = if (index % 2 == 0) AzureTtsClient.VOICE_MALE else AzureTtsClient.VOICE_FEMALE
            val pcmBytes = try {
                azure.synthesizeToPcm(seg.text, voice)
            } catch (e: Exception) {
                // Bitta segmentdagi TTS xatosi butun jarayonni to'xtatmasin —
                // shu segment sukunat holida qoladi, qolganlari davom etadi.
                ByteArray(0)
            }
            if (pcmBytes.isEmpty()) continue

            val startSample = (seg.start * sampleRate).toInt().coerceIn(0, timeline.size)
            val nextStartSample = if (index + 1 < translated.size) {
                (translated[index + 1].start * sampleRate).toInt().coerceIn(0, timeline.size)
            } else {
                timeline.size
            }
            val availableSamples = (nextStartSample - startSample).coerceAtLeast(0)

            val pcmSamples = pcmBytes.size / 2
            val samplesToCopy = minOf(pcmSamples, availableSamples)

            for (s in 0 until samplesToCopy) {
                val byteIndex = s * 2
                val sampleValue = ((pcmBytes[byteIndex + 1].toInt() shl 8) or
                    (pcmBytes[byteIndex].toInt() and 0xFF)).toShort()
                val timelineIndex = startSample + s
                if (timelineIndex in timeline.indices) {
                    timeline[timelineIndex] = sampleValue
                }
            }
        }
        onStep(PipelineStep.DUB_AUDIO_READY)

        val pcmOut = ByteBuffer.allocate(timeline.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in timeline) pcmOut.putShort(sample)

        val dubbedAacFile = File(workDir, "dubbed_audio.m4a")
        AudioTools.encodePcmToAac(pcmOut.array(), sampleRate, dubbedAacFile)

        val outDir = context.getExternalFilesDir(null) ?: context.filesDir
        val outputFile = File(outDir, "dublyaj_${System.currentTimeMillis()}.mp4")
        val muxOk = AudioTools.muxVideoWithNewAudio(videoPath, dubbedAacFile, outputFile)
        if (!muxOk) throw Exception("Yakuniy videoni yig'ib bo'lmadi")
        onStep(PipelineStep.MUXED)

        outputFile
    }
}
