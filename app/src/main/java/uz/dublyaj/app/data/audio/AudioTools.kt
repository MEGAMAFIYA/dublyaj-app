package uz.dublyaj.app.data.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
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

    /**
     * Videodagi audio trekni (kodekidan qat'iy nazar — AAC, MP3, Opus va h.k.)
     * DEKODLAB, WAV faylga yozadi.
     *
     * Nega remux emas, dekodlash: birinchi versiyada audio trekni
     * MediaMuxer orqali to'g'ridan-to'g'ri MP4 konteyneriga ko'chirishga
     * harakat qilingandi — lekin ba'zi videolarning audio kodeki (masalan
     * MP3 yoki Opus) MediaMuxer'ning MP4-chiqish rejimida to'g'ridan-to'g'ri
     * qo'llab-quvvatlanmasligi mumkin, va bu xatoni umumiy "audio trek
     * topilmadi" degan xabar bilan yashirib qo'yardi. Dekodlab-WAV-yozish
     * kodekdan qat'iy nazar ishlaydi, chunki MediaCodec dekoderi deyarli
     * barcha keng tarqalgan audio kodeklarni qo'llab-quvvatlaydi, WAV esa
     * hech qanday kodlashni talab qilmaydi (xom PCM + sarlavha, xolos).
     * Groq/Whisper WAV formatini to'g'ridan-to'g'ri qabul qiladi.
     *
     * Xato yuz bersa, HAQIQIY sababni ko'rsatuvchi Exception tashlaydi
     * (avvalgidek jimgina "false" qaytarish o'rniga) — shunda foydalanuvchi
     * ekranida aniq nima noto'g'ri ketganini ko'rish mumkin.
     */
    fun decodeAudioTrackToWav(videoPath: String, outputFile: File) {
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

        if (pcmOutput.size() == 0) {
            throw Exception("Dekodlangan audio bo'sh chiqdi")
        }

        writeWavFile(outputFile, pcmOutput.toByteArray(), sampleRate, channelCount)
    }

    private fun writeWavFile(outputFile: File, pcmData: ByteArray, sampleRate: Int, channelCount: Int) {
        val byteRate = sampleRate * channelCount * 2
        val blockAlign = channelCount * 2
        val dataSize = pcmData.size
        val chunkSize = 36 + dataSize

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(chunkSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(channelCount.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(16) // bit depth
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)

        FileOutputStream(outputFile).use { out ->
            out.write(header.array())
            out.write(pcmData)
        }
    }

    /**
     * Berilgan RAW PCM (16-bit, mono, sampleRate) baytlarni AAC formatiga
     * kodlab, bitta audio trekli m4a faylga yozadi.
     */
    fun encodePcmToAac(pcm: ByteArray, sampleRate: Int, outputFile: File) {
        val mime = MediaFormat.MIMETYPE_AUDIO_AAC
        val format = MediaFormat.createAudioFormat(mime, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96000)
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
