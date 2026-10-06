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
            // Forma habitual: FA 0E 02 .. .. + bloques de 320 bytes.
            if (nal.size >= 5 + ALAW_CHUNK && nal[1] == 0x0E.toByte() && nal[2] == 0x02.toByte()) {
                var off = 5
                while (off + ALAW_CHUNK <= nal.size) { out.write(nal, off, ALAW_CHUNK); off += ALAW_CHUNK }
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
}
