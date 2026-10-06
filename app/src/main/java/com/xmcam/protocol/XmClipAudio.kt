package com.xmcam.protocol

import java.io.ByteArrayOutputStream

/**
 * Extrae el audio G.711 A-law (8 kHz, mono) de un clip de la SD en formato propietario XM.
 * El audio va en unidades con marcador 0xFA ("FA 0E 02 .. ..") seguidas de bloques de 320 bytes (40 ms).
 * Kotlin/JVM puro. Método adaptado de voidnullvalue/Icsee-android (licencia MIT).
 */
object XmClipAudio {
    /** Bytes por bloque de audio: 40 ms a 8 kHz. */
    private const val ALAW_CHUNK = 320

    /**
     * Concatena el audio A-law de las NAL [nals] (ya separadas con [XmClipParser.splitAnnexB]).
     * @return los bytes A-law, o un array vacío si el clip no lleva audio.
     */
    fun extractAlaw(nals: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (nal in nals) {
            if (nal.isEmpty() || (nal[0].toInt() and 0xFF) != 0xFA) continue
            // Forma habitual: FA 0E 02 .. .. + audio A-law. Se toma TODO lo que sigue a la cabecera de 5 bytes
            // (antes solo bloques completos de 320 bytes y se perdía el resto); en A-law cada byte es una muestra.
            if (nal.size > 5 && nal[1] == 0x0E.toByte() && nal[2] == 0x02.toByte()) {
                out.write(nal, 5, nal.size - 5)
                continue
            }
            // Caso general: salta una subcabecera corta y toma el resto en bloques de 320 bytes.
            for (hdr in intArrayOf(1, 5, 8, 9, 15, 16)) {
                val usable = (nal.size - hdr).let { it - it % ALAW_CHUNK }
                if (nal.size > hdr && usable >= ALAW_CHUNK) { out.write(nal, hdr, usable); break }
            }
        }
        return out.toByteArray()
    }

    /** Decodifica A-law (ITU-T G.711) a PCM de 16 bits. */
    fun alawToPcm(alaw: ByteArray): ShortArray = ShortArray(alaw.size) { i ->
        val a = (alaw[i].toInt() and 0xFF) xor 0x55
        var t = (a and 0x0F) shl 4
        when (val seg = (a and 0x70) shr 4) {
            0 -> t += 8
            1 -> t += 0x108
            else -> { t += 0x108; t = t shl (seg - 1) }
        }
        (if (a and 0x80 != 0) t else -t).toShort()
    }

    /**
     * Resumen de diagnóstico para Logcat: cuántas NAL hay por primer byte (las 8 más frecuentes), cuántas son de audio (0xFA),
     * sus tamaños más repetidos y los primeros 12 bytes de las 3 primeras. Sirve para ajustar [extractAlaw] a un firmware nuevo.
     */
    fun diagnose(nals: List<ByteArray>): String {
        val byFirst = nals.filter { it.isNotEmpty() }.groupingBy { it[0].toInt() and 0xFF }.eachCount()
            .entries.sortedByDescending { it.value }.take(8).joinToString { "%02X×%d".format(it.key, it.value) }
        val audio = nals.filter { it.isNotEmpty() && (it[0].toInt() and 0xFF) == 0xFA }
        val sizes = audio.groupingBy { it.size }.eachCount().entries.sortedByDescending { it.value }.take(5)
            .joinToString { "${it.key}B×${it.value}" }
        val heads = audio.take(3).joinToString(" | ") { n -> n.take(12).joinToString(" ") { "%02X".format(it) } }
        return "primer byte: [$byFirst]; NAL 0xFA: ${audio.size}; tamaños: [$sizes]; cabeceras: [$heads]"
    }
}
