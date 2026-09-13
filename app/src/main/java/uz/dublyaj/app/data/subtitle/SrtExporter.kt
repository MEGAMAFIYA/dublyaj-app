package uz.dublyaj.app.data.subtitle

import uz.dublyaj.app.data.model.TranscriptSegment
import java.io.File
import java.util.Locale

/** Tarjima segmentlaridan standart .srt subtitr fayli yaratadi. */
object SrtExporter {

    fun export(segments: List<TranscriptSegment>, outputFile: File) {
        val sb = StringBuilder()
        segments.forEachIndexed { index, seg ->
            val text = seg.text.trim()
            if (text.isEmpty()) return@forEachIndexed
            sb.append(index + 1).append('\n')
            sb.append(formatTimestamp(seg.start))
                .append(" --> ")
                .append(formatTimestamp(seg.end))
                .append('\n')
            sb.append(text).append("\n\n")
        }
        outputFile.writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun formatTimestamp(seconds: Double): String {
        val totalMs = (seconds * 1000).toLong().coerceAtLeast(0)
        val hours = totalMs / 3_600_000
        val minutes = (totalMs % 3_600_000) / 60_000
        val secs = (totalMs % 60_000) / 1000
        val millis = totalMs % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, secs, millis)
    }
}
