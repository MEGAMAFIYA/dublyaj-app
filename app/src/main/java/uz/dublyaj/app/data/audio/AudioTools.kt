package uz.dublyaj.app.data.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FFmpegsiz, faqat Android SDK'ning o'z android.media API'lari (MediaExtractor,
 * MediaCodec, MediaMuxer) orqali ishlaydigan audio/video vositalari.
 *
 * Diqqat: bu funksiyalar endi content:// Uri emas, oddiy FAYL YO'LI (String)
 * bilan ishlaydi. Sabab: SAF (content://) ruxsatlari ba'zi qurilmalarda
 * (ayniqsa MIUI/Xiaomi) "Permission Denial" xatosiga olib kelgan edi.
 * Video endi tanlangan zahoti ilovaning o'z ichki papkasiga nusxalanadi
 * (bu haqda KinoScreen.kt'ga qarang), shu sabab bu yerda hech qanday
 * tashqi ruxsat kerak emas — oddiy MediaExtractor.setDataSource(path) yetadi.
 */
object AudioTools {

    fun getVideoDurationMs(videoPath: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoPath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }

    /** Dekodlangan mono PCM + uning sample-reyti (pitch tahlili uchun ham qayta ishlatiladi). */
    data class DecodedMonoAudio(val pcm: ShortArray, val sampleRate: Int)

    /**
     * Bir marta dekodlashdan olingan barcha audio hosilalari:
     * - transcriptionFile: Groq/Whisper'ga yuklash uchun siqilgan (mono, 16kHz) AAC fayl
     * - pitchAudio: xuddi shu 16kHz mono PCM — PitchAnalyzer uchun qayta ishlatiladi
     * - backgroundPcm: asl audio, mono, Azure TTS bilan BIR XIL sample-reytda (24kHz) —
     *   fon ovozi/musiqani dublyaj ustiga past balandlikda aralashtirish uchun
     */
    data class PreparedAudio(
        val transcriptionFile: File,
        val pitchAudio: DecodedMonoAudio,
        val backgroundPcm: ShortArray,
        val backgroundSampleRate: Int
    )

    /**
     * Videodagi audio trekni (kodekidan qat'iy nazar — AAC, MP3, Opus va h.k.)
     * BIR MARTA dekodlaydi, so'ng ikkita maqsad uchun ikki xil sample-reytga
     * qayta namunalaydi: (1) Whisper'ga yuklash uchun siqilgan 16kHz mono AAC,
     * (2) fon ovozi sifatida keyinroq dublyaj bilan aralashtirish uchun 24kHz
     * mono xom PCM (Azure TTS chiqishi bilan bir xil reytda — aralashtirishda
     * qayta namunalash shart bo'lmasin deb).
     *
     * Nega mono+16kHz+siqilgan (transkripsiya uchun): Whisper ichki jarayonda
     * baribir 16kHz'ga tushiradi, shuning uchun undan yuqori sifatda yuklashning
     * foydasi yo'q — faqat fayl hajmini oshiradi (va Groq'ning yuklash hajmi
     * chegarasidan, HTTP 413, oshib ketishga sabab bo'lardi).
     *
     * Xato yuz bersa, HAQIQIY sababni ko'rsatuvchi Exception tashlaydi.
     */
    fun prepareAudio(videoPath: String, transcriptionOutputFile: File): PreparedAudio {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(videoPath)
        } catch (e: Exception) {
            extractor.release()
            throw Exception("Video faylni o'qishda xato: ${e.message}", e)
        }

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex == -1 || format == null) {
            extractor.release()
            throw Exception("Bu videoda audio trek yo'q")
        }
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val decoder = try {
            MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
        } catch (e: Exception) {
            extractor.release()
            throw Exception("Audio dekoderini ishga tushirib bo'lmadi ($mime): ${e.message}", e)
        }

        val pcmOutput = ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val timeoutUs = 10_000L

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inIndex)!!
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = decoder.outputFormat
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    outIndex >= 0 -> {
                        val outputBuffer = decoder.getOutputBuffer(outIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.get(chunk)
                            pcmOutput.write(chunk)
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            throw Exception("Audio dekodlashda xato: ${e.message}", e)
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
            extractor.release()
        }

        val rawPcm = pcmOutput.toByteArray()
        if (rawPcm.isEmpty()) {
            throw Exception("Dekodlangan audio bo'sh chiqdi")
        }

        val pitchSampleRate = 16000
        val pitchShorts = downmixAndResample(rawPcm, sampleRate, channelCount, pitchSampleRate)
        val pitchBytes = shortsToLittleEndianBytes(pitchShorts)
        // Nutq uchun past bitreyt (48kbps) yetarli va fayl hajmini yanada kamaytiradi.
        encodePcmToAac(pitchBytes, pitchSampleRate, transcriptionOutputFile, bitRate = 48_000)

        val backgroundSampleRate = AzureTtsSampleRate
        val backgroundShorts = downmixAndResample(rawPcm, sampleRate, channelCount, backgroundSampleRate)

        return PreparedAudio(
            transcriptionFile = transcriptionOutputFile,
            pitchAudio = DecodedMonoAudio(pitchShorts, pitchSampleRate),
            backgroundPcm = backgroundShorts,
            backgroundSampleRate = backgroundSampleRate
        )
    }

    // Azure TTS RAW PCM chiqishi bilan bir xil (24kHz) — shu bilan fon ovozini
    // qo'shimcha qayta namunalashsiz to'g'ridan-to'g'ri aralashtirish mumkin.
    // (data/network/AzureTtsClient.SAMPLE_RATE bilan bir xil qiymat — bu yerga
    // to'g'ridan-to'g'ri bog'lanish "audio" modulini "network" moduliga bog'lab
    // qo'ymaslik uchun konstanta sifatida takrorlangan.)
    private const val AzureTtsSampleRate = 24000

    /**
     * Ovoz balandligini (tempo) o'zgartiradi — chiziqli interpolyatsiya bilan
     * qayta namunalash orqali. Diqqat: bu usul balandlikni (pitch) ham biroz
     * o'zgartiradi (tezlashtirilsa ovoz balandroq eshitiladi) — professional
     * studiyalarda ishlatiladigan pitch-saqlovchi usul (WSOLA/faza-vokoder)
     * emas, lekin sodda va so'zni "kesib tashlash"dan ko'ra ancha tabiiyroq.
     */
    fun changeSpeed(pcm: ShortArray, factor: Double): ShortArray {
        if (pcm.isEmpty() || kotlin.math.abs(factor - 1.0) < 0.01) return pcm
        val safeFactor = factor.coerceIn(0.5, 2.0)
        val dstLength = (pcm.size / safeFactor).toInt().coerceAtLeast(1)
        return ShortArray(dstLength) { i ->
            val srcPos = i * safeFactor
            val idx0 = srcPos.toInt().coerceIn(0, pcm.size - 1)
            val idx1 = (idx0 + 1).coerceAtMost(pcm.size - 1)
            val frac = srcPos - idx0
            (pcm[idx0] * (1 - frac) + pcm[idx1] * frac).toInt().toShort()
        }
    }

    /** RAW PCM (16-bit, little-endian) baytlarni ShortArray'ga aylantiradi. */
    fun bytesToShortsLE(bytes: ByteArray): ShortArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val shorts = ShortArray(bytes.size / 2)
        for (i in shorts.indices) shorts[i] = buffer.getShort(i * 2)
        return shorts
    }

    /**
     * Ko'p kanalli (masalan stereo) PCM'ni mono'ga aylantiradi (kanallar
     * o'rtachasi) va chiziqli interpolyatsiya bilan boshqa sample-reytga
     * qayta namunalaydi (resample). Nutq (STT) uchun bu sifat darajasi
     * to'liq yetarli.
     */
    private fun downmixAndResample(
        pcm: ByteArray,
        srcSampleRate: Int,
        srcChannels: Int,
        dstSampleRate: Int
    ): ShortArray {
        val channels = srcChannels.coerceAtLeast(1)
        val bytesPerSample = 2
        val frameSize = bytesPerSample * channels
        val frameCount = pcm.size / frameSize
        if (frameCount == 0) return ShortArray(0)

        val src = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val mono = ShortArray(frameCount)
        for (i in 0 until frameCount) {
            var sum = 0
            for (c in 0 until channels) {
                sum += src.getShort(i * frameSize + c * bytesPerSample).toInt()
            }
            mono[i] = (sum / channels).toShort()
        }

        return if (srcSampleRate == dstSampleRate) {
            mono
        } else {
            val ratio = srcSampleRate.toDouble() / dstSampleRate.toDouble()
            val dstLength = (mono.size / ratio).toInt().coerceAtLeast(1)
            ShortArray(dstLength) { i ->
                val srcPos = i * ratio
                val idx0 = srcPos.toInt().coerceIn(0, mono.size - 1)
                val idx1 = (idx0 + 1).coerceAtMost(mono.size - 1)
                val frac = srcPos - idx0
                (mono[idx0] * (1 - frac) + mono[idx1] * frac).toInt().toShort()
            }
        }
    }

    private fun shortsToLittleEndianBytes(shorts: ShortArray): ByteArray {
        val out = ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in shorts) out.putShort(s)
        return out.array()
    }

    /**
     * Berilgan RAW PCM (16-bit, mono, sampleRate) baytlarni AAC formatiga
     * kodlab, bitta audio trekli m4a faylga yozadi.
     */
    fun encodePcmToAac(pcm: ByteArray, sampleRate: Int, outputFile: File, bitRate: Int = 96_000) {
        val mime = MediaFormat.MIMETYPE_AUDIO_AAC
        val format = MediaFormat.createAudioFormat(mime, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        }

        val codec = MediaCodec.createEncoderByType(mime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerTrackIndex = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        var inputOffset = 0
        var presentationTimeUs = 0L
        val bytesPerSample = 2
        var inputDone = false
        var outputDone = false
        val timeoutUs = 10_000L

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputBufferIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                        inputBuffer.clear()
                        val remaining = pcm.size - inputOffset
                        if (remaining <= 0) {
                            codec.queueInputBuffer(
                                inputBufferIndex, 0, 0, presentationTimeUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val chunkSize = minOf(inputBuffer.capacity(), remaining)
                            inputBuffer.put(pcm, inputOffset, chunkSize)
                            codec.queueInputBuffer(inputBufferIndex, 0, chunkSize, presentationTimeUs, 0)
                            inputOffset += chunkSize
                            val samplesWritten = chunkSize / bytesPerSample
                            presentationTimeUs += (samplesWritten * 1_000_000L) / sampleRate
                        }
                    }
                }

                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                when {
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxerTrackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outputBufferIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)!!
                        if (bufferInfo.size > 0 && muxerStarted) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(muxerTrackIndex, outputBuffer, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { muxer.stop() }
            muxer.release()
        }
    }

    /**
     * Asl videoning video trekini (o'zgarishsiz nusxalab) va yangi dublyaj
     * audio trekini (m4a) birlashtirib, yakuniy mp4 fayl yaratadi.
     */
    fun muxVideoWithNewAudio(
        originalVideoPath: String,
        dubbedAudioFile: File,
        outputFile: File
    ): Boolean {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        return try {
            videoExtractor.setDataSource(originalVideoPath)
            audioExtractor.setDataSource(dubbedAudioFile.absolutePath)

            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }
            if (videoTrackIndex == -1 || videoFormat == null) return false
            videoExtractor.selectTrack(videoTrackIndex)

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }
            if (audioTrackIndex == -1 || audioFormat == null) return false
            audioExtractor.selectTrack(audioTrackIndex)

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerVideoTrack = muxer.addTrack(videoFormat)
            val muxerAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            var videoDone = false
            var audioDone = false

            // Ikkala trekni vaqt tamg'asi (timestamp) bo'yicha o'zaro
            // qatlamlab (interleave) yozamiz — bu ba'zi pleyerlarda
            // yumshoqroq ijro etilishini ta'minlaydi.
            while (!videoDone || !audioDone) {
                val videoTime = if (videoDone) Long.MAX_VALUE else videoExtractor.sampleTime
                val audioTime = if (audioDone) Long.MAX_VALUE else audioExtractor.sampleTime

                if (!videoDone && (audioDone || videoTime <= audioTime)) {
                    buffer.clear()
                    val size = videoExtractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        videoDone = true
                    } else {
                        bufferInfo.offset = 0
                        bufferInfo.size = size
                        bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                        bufferInfo.flags = videoExtractor.sampleFlags
                        muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)
                        videoExtractor.advance()
                    }
                } else if (!audioDone) {
                    buffer.clear()
                    val size = audioExtractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        audioDone = true
                    } else {
                        bufferInfo.offset = 0
                        bufferInfo.size = size
                        bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                        bufferInfo.flags = audioExtractor.sampleFlags
                        muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                        audioExtractor.advance()
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            videoExtractor.release()
            audioExtractor.release()
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }
}
