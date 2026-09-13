package uz.dublyaj.app.data.audio

import kotlin.math.abs

/**
 * Haqiqiy spiker-diarizatsiya (masalan pyannote) telefonda serversiz ishlashi
 * uchun og'ir model va murakkab pipeline talab qiladi. Shu sabab bu yerda
 * ANCHA YENGIL, lekin amaliy natija beradigan usul qo'llanadi: har bir
 * segmentning o'rtacha asosiy tovush chastotasi (pitch/F0) hisoblanadi —
 * past pitch odatda erkak, baland pitch odatda ayol tovushini bildiradi.
 *
 * Bu real spiker-identifikatsiya EMAS (ikkita bir xil jinsdagi odamni
 * farqlay olmaydi), lekin ilovaning ikki ovozli (erkak/ayol) palitrasi
 * uchun juda mos — asosiy shikoyat ("hammasi bitta ovozda gapiradi")ni
 * to'g'ridan-to'g'ri hal qiladi.
 */
object PitchAnalyzer {

    /**
     * Berilgan segment uchun o'rtacha (median) F0'ni hertsda qaytaradi.
     * Agar segment juda qisqa yoki tovush aniqlanmasa (sukunat, shovqin), null.
     */
    fun estimateMedianPitchHz(pcm: ShortArray, sampleRate: Int, startSample: Int, endSample: Int): Double? {
        val s = startSample.coerceIn(0, pcm.size)
        val e = endSample.coerceIn(s, pcm.size)
        val minSamplesNeeded = (sampleRate * 0.08).toInt()
        if (e - s < minSamplesNeeded) return null

        // Hisoblashni tezlashtirish uchun 2x kamaytirib (decimate) tahlil qilamiz —
        // odam ovozi F0 diapazoni (70-400Hz) uchun bu yetarlicha aniq.
        val decimatedRate = sampleRate / 2
        val frameSizeMs = 0.04
        val frameSize = (decimatedRate * frameSizeMs).toInt().coerceAtLeast(32)
        val hopSize = frameSize // qatlamlanmasdan (overlap yo'q) — tezroq

        val pitches = mutableListOf<Double>()
        var pos = s
        var framesAnalyzed = 0
        val maxFrames = 80 // juda uzun segmentlarda ham vaqt chegaralangan bo'lsin

        while (pos + frameSize * 2 <= e && framesAnalyzed < maxFrames) {
            val frame = DoubleArray(frameSize)
            for (i in 0 until frameSize) {
                frame[i] = pcm[pos + i * 2].toDouble() // decimatsiya: har ikkinchi sample
            }
            val pitch = autocorrelationPitch(frame, decimatedRate)
            if (pitch != null) pitches.add(pitch)
            pos += hopSize * 2
            framesAnalyzed++
        }

        if (pitches.size < 3) return null // ishonchli xulosa uchun yetarli emas
        pitches.sort()
        return pitches[pitches.size / 2]
    }

    private fun autocorrelationPitch(frame: DoubleArray, sampleRate: Int): Double? {
        val mean = frame.average()
        for (i in frame.indices) frame[i] -= mean

        var energy = 0.0
        for (v in frame) energy += v * v
        // Sukunat yoki juda past energiyali kadr — ishonchsiz.
        if (energy < frame.size * 4.0) return null

        // Odam ovozi F0 diapazoni: taxminan 70Hz - 400Hz.
        val minLag = (sampleRate / 400).coerceAtLeast(2)
        val maxLag = (sampleRate / 70).coerceAtMost(frame.size - 1)
        if (maxLag <= minLag) return null

        var bestLag = -1
        var bestValue = 0.0
        for (lag in minLag..maxLag) {
            var sum = 0.0
            for (i in 0 until frame.size - lag) {
                sum += frame[i] * frame[i + lag]
            }
            if (sum > bestValue) {
                bestValue = sum
                bestLag = lag
            }
        }
        if (bestLag <= 0 || bestValue < energy * 0.3) return null // yetarlicha davriy emas

        return sampleRate.toDouble() / bestLag
    }

    /**
     * Berilgan pitch qiymatlarini ikki guruhga (past/baland) ajratadi
     * (oddiy 1-o'lchamli k-means, k=2). Natija: har bir kirish uchun 0
     * (past pitch guruhi — erkak ehtimoli) yoki 1 (baland pitch guruhi —
     * ayol ehtimoli) qiymatidan iborat ro'yxat, kirish tartibida.
     */
    fun clusterIntoTwoGroups(pitches: List<Double>): List<Int> {
        if (pitches.isEmpty()) return emptyList()
        if (pitches.size == 1) return listOf(0)

        var c0 = pitches.min()
        var c1 = pitches.max()
        if (abs(c0 - c1) < 1e-6) return pitches.map { 0 } // hammasi bir xil — bitta guruh

        var assignments = IntArray(pitches.size)
        repeat(15) {
            for (i in pitches.indices) {
                assignments[i] = if (abs(pitches[i] - c0) <= abs(pitches[i] - c1)) 0 else 1
            }
            val group0 = pitches.filterIndexed { i, _ -> assignments[i] == 0 }
            val group1 = pitches.filterIndexed { i, _ -> assignments[i] == 1 }
            if (group0.isNotEmpty()) c0 = group0.average()
            if (group1.isNotEmpty()) c1 = group1.average()
        }

        // Guruh 0 har doim PAST pitch (erkak ehtimoli) bo'lishini kafolatlaymiz.
        return if (c0 <= c1) assignments.toList() else assignments.map { 1 - it }
    }
}
