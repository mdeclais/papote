package io.github.mdeclais.papote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZoneId

class ExportsTest {
    private val paris = ZoneId.of("Europe/Paris")

    private fun session() = Session(
        id = "Doc_2026-11-01_143000", startDateMs = 0, startTOD = 14 * 3600 + 30 * 60 + 0.5,
        sampleRate = 48_000.0, fps = 50, localeID = "fr-FR", duration = 75.0,
        segments = listOf(
            Segment(start = 1.0, end = 3.2, text = "Bonjour à vous", speaker = 0),
            Segment(start = 4.0, end = 4.1, text = "Merci", speaker = 1),
        ),
        speakers = listOf("Marc", "Invitée"),
        markers = listOf(Marker(time = 10.0, name = "Extrait 1", duration = 2.5)),
        finished = true,
    )

    @Test fun timecode() {
        assertEquals("14:30:00:25", Timecode.string(14 * 3600 + 30 * 60 + 0.5, 50))
        assertEquals("00:00:01:00", Timecode.string(86_401.0, 25))   // repli à minuit
        assertEquals("01:00:01,000", Timecode.srt(3601.0))
        assertEquals("0:01:15", Timecode.duration(75.9))
    }

    @Test fun srtBothVariants() {
        val s = session()
        val oneHour = Exports.srt(s, 3600.0, speakerNames = true)
        assertEquals("1\n01:00:01,000 --> 01:00:03,200\nMarc : Bonjour à vous\n\n2\n01:00:04,000 --> 01:00:04,500\nInvitée : Merci\n\n", oneHour)
        val tod = Exports.srt(s, null, speakerNames = false)
        assertTrue(tod.startsWith("1\n14:30:01,500 --> 14:30:03,700\nBonjour à vous\n"))
    }

    @Test fun edlMatchesResolveFormat() {
        val edl = Exports.edl(session(), 3600.0)
        assertEquals(
            "TITLE: Doc_2026-11-01_143000\nFCM: NON-DROP FRAME\n\n" +
            "001  001      V     C        01:00:10:00 01:00:12:25 01:00:10:00 01:00:12:25  \n" +
            " |C:ResolveColorBlue |M:Extrait 1 |D:125\n\n", edl)
        assertTrue(Exports.edl(session(), null).contains("14:30:10:25 14:30:13:00"))
    }

    @Test fun jsonRoundTrip() {
        val s = session()
        assertEquals(s, Session.fromJson(JSONObject(s.toJson().toString())))
    }

    @Test fun jsonFromIPhone() {
        val ios = """{"duration":12.5,"fps":25,"id":"ITW_2026-10-31_101500","localeID":"fr-FR","sampleRate":48000,
            "segments":[{"end":2,"id":"A","start":1,"text":"Salut"}],"startDate":"2026-10-31T09:15:00Z","startTOD":36900.25}"""
        val s = Session.fromJson(JSONObject(ios))
        assertEquals(1, s.segments.size)
        assertEquals(null, s.speakers)
        assertTrue(s.finished)
    }

    @Test fun slugAndId() {
        assertEquals("Doc-Ferme-du-Bec", Store.slug("Doc Ferme du Bec"))
        assertEquals("Ete-a-Noel", Store.slug("Été à Noël"))
        assertEquals("ITW_1970-01-01_010000", Store.makeID(0, null, paris))
    }

    @Test fun midnight() {
        // 2026-11-01 14:30:00.500 à Paris (UTC+1).
        val ms = java.time.ZonedDateTime.of(2026, 11, 1, 14, 30, 0, 500_000_000, paris).toInstant().toEpochMilli().toDouble()
        assertEquals(52_200.5, Timecode.secondsSinceMidnight(ms, paris), 1e-6)
    }

    @Test fun cues() {
        val words = listOf(Word("bonjour", 0.5, 0.9), Word("à", 1.0, 1.1), Word("tous", 1.1, 1.4), Word("alors", 3.0, 3.3))
        val cues = CueBuilder.cues(words)
        assertEquals(2, cues.size)                     // coupure sur le silence de 1,6 s
        assertEquals("Bonjour à tous", cues[0].text)
        assertEquals(0.5, cues[0].start, 0.0)
        assertEquals(1.4, cues[0].end, 0.0)
        // Cas réel de Vosk : le dernier mot de la phrase ne reste pas seul.
        val long = "bonjour je suis très content d'être avec vous aujourd'hui pour parler de ce documentaire".split(" ")
            .mapIndexed { i, t -> Word(t, i * 0.33, i * 0.33 + 0.3) }
        assertEquals(1, CueBuilder.cues(long).size)
        assertEquals(listOf(Word("oui", 0.1, 0.3)),
                     CueBuilder.words("""{"result":[{"conf":1,"end":0.3,"start":0.1,"word":"oui"}],"text":"oui"}"""))
    }

    @Test fun bwfHeaderAndRepair() {
        val f = File.createTempFile("papote", ".wav")
        val tr = 52_200L * 48_000
        val w = BwfWriter(f, 48_000, 2, 24, tr, 0, "Interview test", paris)
        val samples = FloatArray(48_000) { if (it % 2 == 0) 0.5f else -1f }   // 24 000 trames stéréo
        w.append(w.encode(samples, 24_000, 2))
        w.close()
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(BwfWriter.HEADER_SIZE + 24_000 * 6, f.length().toInt())
        assertEquals("bext", String(f.readBytes(), 12, 4, Charsets.US_ASCII))
        assertEquals(tr, b.getInt(12 + 8 + 256 + 32 + 32 + 10 + 8).toLong() and 0xFFFFFFFFL)
        assertEquals(24_000 * 6, b.getInt(BwfWriter.HEADER_SIZE - 4))
        assertEquals(f.length() - 8, b.getInt(4).toLong())
        // Premier échantillon : 0.5 en 24 bits = 4194304 (0x400000).
        assertEquals(0x00, b.get(BwfWriter.HEADER_SIZE).toInt() and 0xFF)
        assertEquals(0x40, b.get(BwfWriter.HEADER_SIZE + 2).toInt() and 0xFF)

        // Fichier coupé net en plein échantillon, en-tête non à jour.
        RandomAccessFile(f, "rw").use { it.setLength(it.length() - 4); it.seek(BwfWriter.HEADER_SIZE - 4L); it.writeInt(0) }
        assertEquals(23_999L, BwfWriter.repair(f))
        val r = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(23_999 * 6, r.getInt(BwfWriter.HEADER_SIZE - 4))
        assertEquals(23_999L, BwfWriter.repair(f))   // idempotent
        f.delete()
    }

    @Test fun downsampler() {
        val d = Downsampler(48_000)
        val out = d.process(FloatArray(4800) { 0.25f }, 4800)
        assertEquals(1600, out.size)
        assertEquals((0.25 * 32_767).toInt().toShort(), out[10])
    }
}
