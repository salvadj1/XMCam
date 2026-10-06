package com.xmcam.data

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.xmcam.protocol.XmClipAudio
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Audio del clip ya codificado en AAC: configuración, muestras y su instante (µs). */
class AacTrack(val csd: ByteArray, val samples: List<ByteArray>, val ptsUs: LongArray)

/** Convierte el audio A-law de un clip XM a AAC para poder meterlo en un MP4. */
object ClipAudio {
    private const val TAG = "XMCamAudio"
    private const val SAMPLE_RATE = 8_000
    private const val FRAME_SAMPLES = 1024 // una trama AAC-LC
    /** Configuración AAC-LC, 8 kHz, mono (por si el codificador no la devuelve). */
    private val DEFAULT_CSD = byteArrayOf(0x15, 0x88.toByte())

    /**
     * Saca el audio de las NAL [nals] del clip y lo codifica a AAC.
     * @return la pista de audio, o null si el clip no tiene audio o la codificación falla.
     */
    fun fromClip(nals: List<ByteArray>): AacTrack? {
        val alaw = XmClipAudio.extractAlaw(nals)
        Log.i(TAG, "A-law extraído: ${alaw.size} bytes de ${nals.size} NAL")
        Log.i(TAG, XmClipAudio.diagnose(nals))
        if (alaw.size < 320) { Log.w(TAG, "Clip sin audio reconocible (ver XmClipAudio.extractAlaw)"); return null }
        return runCatching { encodeAac(XmClipAudio.alawToPcm(alaw)) }
            .onFailure { Log.e(TAG, "Fallo al codificar AAC", it) }
            .getOrNull()
            .also { Log.i(TAG, "AAC: ${it?.samples?.size ?: 0} tramas, csd=${it?.csd?.size ?: 0} bytes") }
    }

    /** Formato de pista de audio para MediaMuxer a partir de la configuración [csd]. */
    fun muxerFormat(csd: ByteArray): MediaFormat =
        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setByteBuffer("csd-0", ByteBuffer.wrap(csd))
        }

    /** Codifica PCM mono de 8 kHz a AAC-LC con MediaCodec. Los instantes salen del propio codificador. */
    private fun encodeAac(pcm: ShortArray): AacTrack? {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 24_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            val samples = ArrayList<ByteArray>()
            val pts = ArrayList<Long>()
            var csd: ByteArray? = null
            var idx = 0
            var inputDone = false
            var outputDone = false
            val deadline = System.currentTimeMillis() + 30_000 // seguro contra bloqueos
            while (!outputDone && System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val inIx = codec.dequeueInputBuffer(10_000)
                    if (inIx >= 0) {
                        val buf = codec.getInputBuffer(inIx)!!
                        buf.clear(); buf.order(ByteOrder.LITTLE_ENDIAN)
                        val t = idx * 1_000_000L / SAMPLE_RATE
                        if (idx >= pcm.size) {
                            codec.queueInputBuffer(inIx, 0, 0, t, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val n = minOf(FRAME_SAMPLES, pcm.size - idx, buf.capacity() / 2)
                            for (i in 0 until n) buf.putShort(pcm[idx + i])
                            codec.queueInputBuffer(inIx, 0, n * 2, t, 0)
                            idx += n
                        }
                    }
                }
                val outIx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    csd = codec.outputFormat.getByteBuffer("csd-0")?.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
                } else if (outIx >= 0) {
                    val ob = codec.getOutputBuffer(outIx)!!
                    if (info.size > 0) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size).also { ob.get(it) }
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) csd = bytes
                        else { samples += bytes; pts += info.presentationTimeUs }
                    }
                    codec.releaseOutputBuffer(outIx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            if (samples.isEmpty()) return null
            return AacTrack(csd ?: DEFAULT_CSD, samples, pts.toLongArray())
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }
}
