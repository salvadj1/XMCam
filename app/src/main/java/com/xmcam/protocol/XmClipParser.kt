package com.xmcam.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Extrae el vídeo (H.265 o H.264) de un clip de la SD descargado por DVRIP.
 *
 * El clip llega en formato propietario XM: unidades Annex-B ("00 00 01 <tipo>") donde los tipos
 * 0xF9/0xFA/0xFC/0xFD son envoltorios XM (NAL HEVC reservados 124-126) mezclados con las NAL de
 * vídeo reales (VPS/SPS/PPS/IDR/TRAIL), o bien anidadas dentro de ellos. El parser prueba cada
 * disposición conocida por orden.
 *
 * Kotlin/JVM puro (sin Android): se puede copiar a otros proyectos.
 * Método adaptado de voidnullvalue/Icsee-android (licencia MIT).
 */
object XmClipParser {
    /** Pista HEVC: parámetros y NAL de imagen en orden. */
    class HevcTrack(val vps: ByteArray?, val sps: ByteArray, val pps: ByteArray?, val frames: List<ByteArray>)

    /** Pista H.264: parámetros y NAL de imagen en orden. */
    class AvcTrack(val sps: ByteArray, val pps: ByteArray?, val frames: List<ByteArray>)

    /** Resultado del análisis: HEVC o AVC. */
    sealed interface Parsed {
        class Hevc(val track: HevcTrack) : Parsed
        class Avc(val track: AvcTrack) : Parsed
    }

    /** Analiza los bytes descargados ([top] = NAL ya separadas, para no repetir el trabajo). Lanza [IllegalArgumentException] si no encuentra vídeo válido. */
    fun parse(raw: ByteArray, top: List<ByteArray> = splitAnnexB(raw)): Parsed {
        require(raw.isNotEmpty()) { "El clip descargado está vacío (0 bytes)" }

        // 1) División Annex-B directa (la disposición confirmada en cámaras reales).
        parseHevc(top)?.let { return Parsed.Hevc(it) }
        parseAvc(top)?.let { return Parsed.Avc(it) }

        // 2) Abrir los envoltorios XM y buscar dentro.
        val nested = ArrayList<ByteArray>()
        for (nal in top) {
            if (nal.isEmpty()) continue
            if (hevcType(nal) in 124..126) nested += extractFromWrapper(nal) else nested += nal
        }
        if (nested.isNotEmpty()) {
            parseHevc(nested)?.let { return Parsed.Hevc(it) }
            parseAvc(nested)?.let { return Parsed.Avc(it) }
        }

        // 3) NAL con prefijo de longitud (HVCC/AVCC) sin códigos de inicio.
        val prefixed = splitLengthPrefixed(raw)
        parseHevc(prefixed)?.let { return Parsed.Hevc(it) }
        parseAvc(prefixed)?.let { return Parsed.Avc(it) }

        val types = top.take(32).map { if (it.isEmpty()) -1 else hevcType(it) }
        throw IllegalArgumentException("No se encontró vídeo H.264/H.265 en el clip (${raw.size} bytes, primeros tipos NAL=$types)")
    }

    private fun parseHevc(nals: List<ByteArray>): HevcTrack? {
        var vps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        val frames = ArrayList<ByteArray>()
        for (nal in nals) {
            if (nal.isEmpty()) continue
            when (hevcType(nal)) {
                32 -> vps = nal
                33 -> sps = nal
                34 -> pps = nal
                in 0..31 -> frames.add(nal)
            }
        }
        val s = sps ?: return null
        if (frames.isEmpty()) return null
        return HevcTrack(vps, s, pps, frames)
    }

    private fun parseAvc(nals: List<ByteArray>): AvcTrack? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        val frames = ArrayList<ByteArray>()
        for (nal in nals) {
            if (nal.isEmpty()) continue
            when (nal[0].toInt() and 0x1F) {
                7 -> sps = nal
                8 -> pps = nal
                in 1..5 -> frames.add(nal)
            }
        }
        val s = sps ?: return null
        if (frames.isEmpty()) return null
        return AvcTrack(s, pps, frames)
    }

    /** Cuerpo de un envoltorio XM: prueba varias longitudes de subcabecera y divisiones. */
    private fun extractFromWrapper(wrapper: ByteArray): List<ByteArray> {
        if (wrapper.size < 8) return emptyList()
        for (hdrLen in intArrayOf(0, 4, 8, 12, 15, 16)) {
            if (wrapper.size <= 1 + hdrLen) continue
            val body = wrapper.copyOfRange(1 + hdrLen, wrapper.size)
            val annex = splitAnnexB(body)
            if (annex.any { hasParams(it) }) return annex
            val prefixed = splitLengthPrefixed(body)
            if (prefixed.any { hasParams(it) }) return prefixed
        }
        return if (wrapper.size > 16) splitAnnexB(wrapper.copyOfRange(16, wrapper.size)) else emptyList()
    }

    private fun hasParams(nal: ByteArray) =
        nal.isNotEmpty() && (hevcType(nal) in 32..34 || (nal[0].toInt() and 0x1F) in 7..8)

    /** Divide por códigos de inicio "00 00 01" y devuelve cada NAL sin el código. */
    fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i < data.size - 3) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                starts.add(i + 3); i += 3
            } else i++
        }
        val out = ArrayList<ByteArray>(starts.size)
        for (k in starts.indices) {
            val s = starts[k]
            var e = if (k + 1 < starts.size) starts[k + 1] - 3 else data.size
            if (e > s && data[e - 1].toInt() == 0) e-- // quita el 0 inicial de un código de 4 bytes
            if (e > s) out.add(data.copyOfRange(s, e))
        }
        return out
    }

    private fun splitLengthPrefixed(data: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i + 4 <= data.size && out.size < 100_000) {
            val len = ByteBuffer.wrap(data, i, 4).order(ByteOrder.BIG_ENDIAN).int
            if (len <= 0 || len > data.size || i + 4 + len > data.size) break
            out.add(data.copyOfRange(i + 4, i + 4 + len))
            i += 4 + len
        }
        return out
    }

    private fun hevcType(nal: ByteArray): Int = (nal[0].toInt() shr 1) and 0x3F
}

/** Lector de bits para las cabeceras SPS (con Exp-Golomb). */
internal class BitReader(private val data: ByteArray) {
    private var bit = 0
    fun u(n: Int): Int {
        var v = 0
        repeat(n) {
            val idx = bit ushr 3
            val b = if (idx < data.size) data[idx].toInt() and 0xFF else 0
            v = (v shl 1) or ((b ushr (7 - (bit and 7))) and 1)
            bit++
        }
        return v
    }
    fun skip(n: Int) { bit += n }
    fun ue(): Int {
        var zeros = 0
        while (u(1) == 0 && zeros < 32) zeros++
        return if (zeros == 0) 0 else (1 shl zeros) - 1 + u(zeros)
    }
    fun se(): Int { val v = ue(); return if (v and 1 == 0) -(v ushr 1) else (v + 1) ushr 1 }
}

/** Quita los bytes de prevención de emulación (00 00 03) de una NAL a partir de [from]. */
private fun unescape(nal: ByteArray, from: Int): ByteArray {
    val out = ByteArrayOutputStream()
    var zeros = 0
    for (i in from until nal.size) {
        val b = nal[i].toInt() and 0xFF
        if (zeros >= 2 && b == 0x03 && i + 1 < nal.size && (nal[i + 1].toInt() and 0xFF) <= 0x03) zeros = 0
        else { out.write(b); zeros = if (b == 0) zeros + 1 else 0 }
    }
    return out.toByteArray()
}

/** Lee el ancho y alto codificados de un SPS de H.265. */
object HevcSps {
    /** @return (ancho, alto) en píxeles. */
    fun dimensions(spsNal: ByteArray): Pair<Int, Int> {
        val br = BitReader(unescape(spsNal, 2)) // salta la cabecera NAL de 2 bytes
        br.u(4)                                 // sps_video_parameter_set_id
        val maxSubLayersMinus1 = br.u(3)
        br.u(1)                                 // temporal_id_nesting_flag
        br.skip(96)                             // profile_tier_level general (88 bits) + nivel (8)
        if (maxSubLayersMinus1 > 0) {
            val present = ArrayList<Pair<Boolean, Boolean>>()
            repeat(maxSubLayersMinus1) { present.add((br.u(1) == 1) to (br.u(1) == 1)) }
            for (i in maxSubLayersMinus1 until 8) br.u(2)
            for ((profile, level) in present) { if (profile) br.skip(88); if (level) br.skip(8) }
        }
        br.ue()                                 // sps_seq_parameter_set_id
        if (br.ue() == 3) br.u(1)               // chroma_format_idc / separate_colour_plane_flag
        return br.ue() to br.ue()
    }
}

/** Lee el ancho y alto codificados de un SPS de H.264. */
object AvcSps {
    /** @return (ancho, alto) en píxeles, ya recortados. */
    fun dimensions(spsNal: ByteArray): Pair<Int, Int> {
        val br = BitReader(unescape(spsNal, 1))
        val profile = spsNal[1].toInt() and 0xFF
        br.u(8); br.u(8); br.u(8)               // profile, restricciones, nivel
        br.ue()                                 // seq_parameter_set_id
        if (profile in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            val chroma = br.ue()
            if (chroma == 3) br.u(1)
            br.ue(); br.ue(); br.u(1)           // profundidades de bit y bypass
            if (br.u(1) == 1) skipScalingLists(br, chroma)
        }
        br.ue()                                 // log2_max_frame_num_minus4
        when (br.ue()) {                        // pic_order_cnt_type
            0 -> br.ue()
            1 -> { br.u(1); br.se(); br.se(); repeat(br.ue()) { br.se() } }
        }
        br.ue(); br.u(1)                        // max_num_ref_frames, gaps_in_frame_num
        val wMbs = br.ue() + 1
        val hMapUnits = br.ue() + 1
        val frameMbsOnly = br.u(1)
        if (frameMbsOnly == 0) br.u(1)
        br.u(1)                                 // direct_8x8_inference_flag
        val mult = if (frameMbsOnly == 0) 2 else 1
        var w = wMbs * 16
        var h = hMapUnits * 16 * mult
        if (br.u(1) == 1) {                     // recorte del fotograma
            val l = br.ue(); val r = br.ue(); val t = br.ue(); val b = br.ue()
            w -= (l + r) * 2
            h -= (t + b) * 2 * mult
        }
        return w to h
    }

    private fun skipScalingLists(br: BitReader, chroma: Int) {
        repeat(if (chroma != 3) 8 else 12) { i ->
            if (br.u(1) == 1) {
                var next = 8
                var last = 8
                repeat(if (i < 6) 16 else 64) {
                    if (next != 0) next = (last + br.se() + 256) % 256
                    last = if (next == 0) last else next
                }
            }
        }
    }
}
