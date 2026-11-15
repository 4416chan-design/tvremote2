package com.example

import com.example.data.model.TvAppCatalog
import com.example.data.model.TvKeyCodes
import com.example.network.AndroidTvRemoteClient
import com.example.network.ProtobufWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ProtobufAndCertUnitTest {

    @Test
    fun `protobuf framing roundtrip preserves Korean UTF-8 IME batch edit`() {
        val koreanText = "넷플릭스 오징어 게임"
        val encoded = ProtobufWire.encode {
            writeMessageField(21) {
                writeVarintField(1, 1)
                writeVarintField(2, 0)
                writeMessageField(3) {
                    writeVarintField(1, 2)
                    writeMessageField(2) {
                        writeVarintField(1, 0)
                        writeVarintField(2, 10000)
                        writeStringField(3, koreanText)
                    }
                }
            }
        }

        val out = ByteArrayOutputStream()
        ProtobufWire.writeFramedMessage(out, encoded)

        val framedBytes = ProtobufWire.readFramedMessage(ByteArrayInputStream(out.toByteArray()))
        val root = ProtobufWire.parseFields(framedBytes)
        val batchEdit = root.first { it.number == 21 }.asMessage()
        val editInfo = batchEdit.first { it.number == 3 }.asMessage()
        val imeObj = editInfo.first { it.number == 2 }.asMessage()
        val decodedText = imeObj.first { it.number == 3 }.asString()

        assertEquals(koreanText, decodedText)
    }

    @Test
    fun `remote key inject encodes keycode and short direction`() {
        val encoded = ProtobufWire.encode {
            writeMessageField(10) {
                writeVarintField(1, TvKeyCodes.MEDIA_FAST_FORWARD)
                writeVarintField(2, 3)
            }
        }
        val fields = ProtobufWire.parseFields(encoded)
        val keyInject = fields.first { it.number == 10 }.asMessage()
        assertEquals(TvKeyCodes.MEDIA_FAST_FORWARD.toLong(), keyInject.first { it.number == 1 }.varint)
        assertEquals(3L, keyInject.first { it.number == 2 }.varint)
    }

    @Test
    fun `remote app link launch encodes safe native scheme without market launch`() {
        val uri = TvAppCatalog.launchUriForPackage("com.google.android.youtube.tv")
        assertEquals("vnd.youtube://", uri)
        assertEquals("market://details?id=com.google.android.youtube.tv", TvAppCatalog.marketUriForPackage("com.google.android.youtube.tv"))
        assertTrue(TvAppCatalog.isLegacyBrokenUri("market://launch?id=com.google.android.youtube.tv"))
        assertTrue(TvAppCatalog.isLegacyBrokenUri("https://www.youtube.com/tv"))
        assertEquals(false, TvAppCatalog.isLegacyBrokenUri("vnd.youtube://"))
        val encoded = ProtobufWire.encode {
            writeMessageField(90) {
                writeStringField(1, uri)
            }
        }
        val fields = ProtobufWire.parseFields(encoded)
        assertTrue(fields.any { it.number == 90 })
        val appLink = fields.first { it.number == 90 }.asMessage()
        assertEquals(uri, appLink.first { it.number == 1 }.asString())
    }

    @Test
    fun `negative int32 varint encodes as 64-bit sign-extended two complement`() {
        val encoded = ProtobufWire.encode {
            writeVarintField(1, -1)
        }
        // tag (1 << 3 | 0 = 0x08) + 10-byte two's complement varint for -1L
        assertEquals(11, encoded.size)
        val parsed = ProtobufWire.parseFields(encoded)
        assertEquals(-1L, parsed.first { it.number == 1 }.varint)
    }

    @Test
    fun `adb key manager produces exact 524-byte android rsa struct and 256-byte signature`() {
        val tempDir = java.io.File.createTempFile("adb_test", "").apply {
            delete()
            mkdirs()
        }
        try {
            val manager = com.example.network.AdbKeyManager(tempDir)
            val (_, pubKey) = manager.getOrCreateKeyPair()
            val structBytes = com.example.network.AdbKeyManager.buildAndroidRsaPublicKeyStruct(pubKey)
            assertEquals(524, structBytes.size)

            val payloadStr = manager.buildAdbPublicKeyPayload().toString(Charsets.US_ASCII)
            assertTrue(payloadStr.contains(" tvremote@android\u0000"))

            val token = ByteArray(20) { it.toByte() }
            val sig = manager.signToken(token)
            assertEquals(256, sig.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `tv remote keycodes match Android KeyEvent constants`() {
        assertEquals(62, TvKeyCodes.SPACE)
        assertEquals(67, TvKeyCodes.DEL)
        assertEquals(284, TvKeyCodes.ALL_APPS)
        assertEquals(176, TvKeyCodes.SETTINGS)
        assertEquals(220, TvKeyCodes.BRIGHTNESS_DOWN)
        assertEquals(221, TvKeyCodes.BRIGHTNESS_UP)
    }

    @Test
    fun `remote set volume level parses max level and muted from fields 6 7 8`() {
        val unmutedPayload = ProtobufWire.encode {
            writeMessageField(50) {
                writeVarintField(6, 100) // volume_max
                writeVarintField(7, 18)  // volume_level
                writeVarintField(8, 0)   // volume_muted = false
            }
        }
        val unmutedMsg = ProtobufWire.parseFields(unmutedPayload).first { it.number == 50 }.asMessage()
        val (level, max, muted) = com.example.network.AndroidTvRemoteClient.parseVolumeLevel(unmutedMsg)
        assertEquals(18, level)
        assertEquals(100, max)
        assertEquals(false, muted)

        val mutedPayload = ProtobufWire.encode {
            writeMessageField(50) {
                writeVarintField(6, 100)
                writeVarintField(7, 18)
                writeVarintField(8, 1)   // volume_muted = true
            }
        }
        val mutedMsg = ProtobufWire.parseFields(mutedPayload).first { it.number == 50 }.asMessage()
        val (mLevel, mMax, mMuted) = com.example.network.AndroidTvRemoteClient.parseVolumeLevel(mutedMsg)
        assertEquals(18, mLevel)
        assertEquals(100, mMax)
        assertEquals(true, mMuted)
    }

    @Test
    fun `ime batch edit payload uses insert one full text and cursor at text length`() {
        val payload = com.example.network.AndroidTvRemoteClient.buildImeBatchEditPayload(
            imeCounter = 5,
            fieldCounter = 3,
            text = "유튜브 검색"
        )
        val root = ProtobufWire.parseFields(payload)
        val batchEdit = root.first { it.number == 21 }.asMessage()
        assertEquals(5L, batchEdit.first { it.number == 1 }.varint)
        assertEquals(3L, batchEdit.first { it.number == 2 }.varint)

        val editInfo = batchEdit.first { it.number == 3 }.asMessage()
        assertEquals(1L, editInfo.first { it.number == 1 }.varint)

        val imeObj = editInfo.first { it.number == 2 }.asMessage()
        assertEquals(6L, imeObj.first { it.number == 1 }.varint)
        assertEquals(6L, imeObj.first { it.number == 2 }.varint)
        assertEquals("유튜브 검색", imeObj.first { it.number == 3 }.asString())
    }

    @Test
    fun `ime focus via key inject notifies ui and parses app_package field 12`() {
        var imeShowCalls = 0
        var hiddenCalls = 0
        var lastPkg = ""
        val client = com.example.network.AndroidTvRemoteClient(
            certStore = null,
            onForegroundAppChanged = { lastPkg = it },
            onImeShowRequested = { _, _ -> imeShowCalls++ },
            onImeHidden = { hiddenCalls++ },
            onVolumeChanged = { _, _, _ -> },
            onDisconnected = {}
        )

        fun keyInject(pkg: String, fc: Int?) = ProtobufWire.encode {
            writeMessageField(20) {
                writeMessageField(1) {
                    writeVarintField(1, 1)
                    writeVarintField(3, 7)
                    writeStringField(12, pkg)
                }
                if (fc != null) {
                    writeMessageField(2) {
                        writeVarintField(1, fc)
                        writeStringField(2, "abc")
                    }
                }
            }
        }

        kotlinx.coroutines.runBlocking {
            client.applyIncomingFieldsForTest(ProtobufWire.parseFields(keyInject("com.google.android.youtube.tv", 4)))
        }
        assertEquals("com.google.android.youtube.tv", lastPkg)
        assertEquals(1, imeShowCalls)
        assertTrue(client.isImeFieldActive)
        assertEquals(4, client.debugFieldCounter)

        // Same field again: no duplicate UI notification
        kotlinx.coroutines.runBlocking {
            client.applyIncomingFieldsForTest(ProtobufWire.parseFields(keyInject("com.google.android.youtube.tv", 4)))
        }
        assertEquals(1, imeShowCalls)

        // Switching to another app hides the stale IME field
        kotlinx.coroutines.runBlocking {
            client.applyIncomingFieldsForTest(ProtobufWire.parseFields(keyInject("com.netflix.ninja", null)))
        }
        assertEquals(1, hiddenCalls)
        assertEquals(false, client.isImeFieldActive)
    }

    @Test
    fun `ime batch edit from tv activates field and adopts tv ime counter`() {
        var imeShowCalls = 0
        val client = com.example.network.AndroidTvRemoteClient(
            certStore = null,
            onForegroundAppChanged = {},
            onImeShowRequested = { _, _ -> imeShowCalls++ },
            onVolumeChanged = { _, _, _ -> },
            onDisconnected = {}
        )
        val batch = ProtobufWire.encode {
            writeMessageField(21) {
                writeVarintField(1, 9)
                writeVarintField(2, 2)
            }
        }
        kotlinx.coroutines.runBlocking {
            client.applyIncomingFieldsForTest(ProtobufWire.parseFields(batch))
        }
        assertEquals(1, imeShowCalls)
        assertTrue(client.isImeFieldActive)
        assertEquals(9, client.debugImeCounter)
        assertEquals(2, client.debugFieldCounter)
    }

    @Test
    fun `apply text edit handles full replacement and range edits safely`() {
        assertEquals(
            "새 검색어",
            com.example.network.AndroidTvRemoteClient.applyTextEdit("기존", 0, 2, "새 검색어")
        )
        assertEquals(
            "abXYef",
            com.example.network.AndroidTvRemoteClient.applyTextEdit("abcdef", 2, 4, "XY")
        )
    }

    @Test
    fun `adb app launcher builds safe shell command and rejects injection`() {
        assertEquals(null, com.example.network.NetworkAdbClient.sanitizePackageName("bad; rm -rf /"))
        assertEquals("com.google.android.youtube.tv", com.example.network.NetworkAdbClient.sanitizePackageName(" com.google.android.youtube.tv "))

        val cmd = com.example.network.NetworkAdbClient.buildAppLaunchShellCommand("com.google.android.youtube.tv")!!
        assertTrue(cmd.contains("LEANBACK_LAUNCHER"))
        assertTrue(cmd.contains("market://details?id=\$PKG"))
        assertTrue(cmd.contains("STATUS=APP"))
        assertTrue(cmd.contains("STATUS=STORE"))
    }

    @Test
    fun `adb keyevent builder formats KEYCODE_ALL_APPS 284 and rejects invalid codes`() {
        assertEquals(null, com.example.network.NetworkAdbClient.buildKeyEventShellCommand(0))
        assertEquals(null, com.example.network.NetworkAdbClient.buildKeyEventShellCommand(-1))
        assertEquals(
            "input keyevent 284 2>&1",
            com.example.network.NetworkAdbClient.buildKeyEventShellCommand(TvKeyCodes.ALL_APPS)
        )
    }

    @Test
    fun `adb input text builder escapes spaces single quotes and handles Korean unicode check`() {
        assertTrue(com.example.network.NetworkAdbClient.isAsciiInputSupported("it's youtube 123!"))
        assertEquals(false, com.example.network.NetworkAdbClient.isAsciiInputSupported("오징어 게임"))

        assertEquals(null, com.example.network.NetworkAdbClient.buildInputTextShellCommand("오징어"))

        val cmd = com.example.network.NetworkAdbClient.buildInputTextShellCommand(
            text = "it's tv",
            clearCount = 3,
            submitWithEnter = true
        )
        assertEquals(
            "input keyevent 123 67 67 67; input text 'it'\\''s%stv' 2>&1; input keyevent 66 2>&1",
            cmd
        )

        val clearOnly = com.example.network.NetworkAdbClient.buildInputTextShellCommand(
            text = "",
            clearCount = 2,
            submitWithEnter = false
        )
        assertEquals("input keyevent 123 67 67 2>&1", clearOnly)
    }

    @Test
    fun `remote voice protocol encodes begin payload end and negotiates VOICE feature flag`() {
        assertTrue((com.example.network.AndroidTvRemoteClient.REMOTE_FEATURES and 16) != 0)
        assertEquals(639, com.example.network.AndroidTvRemoteClient.REMOTE_FEATURES)

        val beginIncoming = ProtobufWire.encode {
            writeMessageField(30) {
                writeVarintField(1, 7)
                writeStringField(2, "com.google.android.katniss")
            }
        }
        val beginMsg = ProtobufWire.parseFields(beginIncoming).first { it.number == 30 }.asMessage()
        val (sid, pkg) = com.example.network.AndroidTvRemoteClient.parseVoiceBegin(beginMsg)
        assertEquals(7, sid)
        assertEquals("com.google.android.katniss", pkg)

        val beginReply = ProtobufWire.parseFields(
            com.example.network.AndroidTvRemoteClient.buildVoiceBeginPayload(7)
        ).first { it.number == 30 }.asMessage()
        assertEquals(7L, beginReply.first { it.number == 1 }.varint)

        val pcmSample = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val payloadMsg = ProtobufWire.parseFields(
            com.example.network.AndroidTvRemoteClient.buildVoicePayload(7, pcmSample)
        ).first { it.number == 31 }.asMessage()
        assertEquals(7L, payloadMsg.first { it.number == 1 }.varint)
        assertTrue(pcmSample.contentEquals(payloadMsg.first { it.number == 2 }.bytes))

        val endMsg = ProtobufWire.parseFields(
            com.example.network.AndroidTvRemoteClient.buildVoiceEndPayload(7)
        ).first { it.number == 32 }.asMessage()
        assertEquals(7L, endMsg.first { it.number == 1 }.varint)
    }

    @Test
    fun `ime sync ignores int5 attribute flags and resets tracked text on proto3 empty snapshot`() {
        var imeShowCalls = 0
        val client = com.example.network.AndroidTvRemoteClient(
            certStore = null,
            onForegroundAppChanged = {},
            onImeShowRequested = { _, _ -> imeShowCalls++ },
            onVolumeChanged = { _, _, _ -> },
            onDisconnected = {}
        )

        // TV sends RemoteImeShowRequest(22) with counter_field=3, value="기존", int5=524289 (EditorInfo flags)
        val showBytes = ProtobufWire.encode {
            writeMessageField(22) {
                writeMessageField(2) {
                    writeVarintField(1, 3)
                    writeStringField(2, "기존")
                    writeVarintField(3, 2)
                    writeVarintField(4, 2)
                    writeVarintField(5, 524289)
                }
            }
        }
        kotlinx.coroutines.runBlocking {
            client.applyIncomingFieldsForTest(ProtobufWire.parseFields(showBytes))
        }
        assertEquals(1, imeShowCalls)
        assertTrue(client.isImeFieldActive)
        assertEquals(3, client.debugFieldCounter)
        assertEquals("기존", client.debugTrackedText)
        // CRITICAL: int5 (524289) must NOT overwrite imeCounter; new field starts at imeCounter = 0
        assertEquals(0, client.debugImeCounter)

        // TV sends RemoteImeKeyInject(20) on same field with empty value omitted in proto3 + int5=524289
        val clearedBytes = ProtobufWire.encode {
            writeMessageField(20) {
                writeMessageField(2) {
                    writeVarintField(1, 3)
                    writeVarintField(5, 524289)
                }
            }
        }
        kotlinx.coroutines.runBlocking {
            client.applyIncomingFieldsForTest(ProtobufWire.parseFields(clearedBytes))
        }
        assertEquals("", client.debugTrackedText)
        assertEquals(0, client.debugImeCounter)
        assertEquals(524289, client.debugSyncedTextInt5)
    }

    @Test
    fun `ime sync parses app_package from field 12 and resets active IME when app switches`() {
        var hiddenCalls = 0
        val changedPkgs = mutableListOf<String>()
        val client = com.example.network.AndroidTvRemoteClient(
            certStore = null,
            onForegroundAppChanged = { changedPkgs.add(it) },
            onImeShowRequested = { _, _ -> },
            onImeHidden = { hiddenCalls++ },
            onVolumeChanged = { _, _, _ -> },
            onDisconnected = {}
        )

        val showInLauncher = ProtobufWire.encode {
            writeMessageField(20) {
                writeMessageField(1) {
                    writeVarintField(3, 1)
                    writeStringField(10, "Android TV Home")
                    writeStringField(12, "com.google.android.tvlauncher")
                }
            }
            writeMessageField(22) {
                writeMessageField(2) {
                    writeVarintField(1, 0) // Shield-style fieldCounter = 0
                    writeVarintField(5, 1)
                    writeStringField(6, "Search")
                }
            }
        }
        kotlinx.coroutines.runBlocking {
            client.applyIncomingFieldsForTest(ProtobufWire.parseFields(showInLauncher))
        }
        assertEquals("com.google.android.tvlauncher", client.debugForegroundPackage)
        assertTrue(client.isImeFieldActive)
        assertEquals(0, client.resolvedImeFieldCounter())
        assertEquals("Search", client.debugSyncedTextLabel)

        val switchToYoutube = ProtobufWire.encode {
            writeMessageField(20) {
                writeMessageField(1) {
                    writeStringField(12, "com.google.android.youtube.tv")
                }
            }
        }
        kotlinx.coroutines.runBlocking {
            client.applyIncomingFieldsForTest(ProtobufWire.parseFields(switchToYoutube))
        }
        assertEquals("com.google.android.youtube.tv", client.debugForegroundPackage)
        assertEquals(false, client.isImeFieldActive)
        assertEquals(1, hiddenCalls)
        assertEquals(listOf("com.google.android.tvlauncher", "com.google.android.youtube.tv"), changedPkgs)
    }

    @Test
    fun `sendText and sendKeyboardChars emit single RemoteImeBatchEdit frame without client key_inject`() {
        val sentChunks = mutableListOf<ByteArray>()
        val client = AndroidTvRemoteClient(
            certStore = null,
            onForegroundAppChanged = {},
            onImeShowRequested = { _, _ -> },
            onVolumeChanged = { _, _, _ -> },
            onDisconnected = {}
        ).apply {
            testRawBytesSink = { bytes -> sentChunks.add(bytes) }
        }

        // Before any TV IME packet (e.g., YouTube): default counters are (0, 0) and stay (0, 0) across sends
        assertEquals(0, client.resolvedImeFieldCounter())
        kotlinx.coroutines.runBlocking {
            client.sendText("bts", replaceExisting = true, submitWithEnter = false)
            client.sendText("오징어", replaceExisting = true, submitWithEnter = false)
        }
        assertEquals(2, sentChunks.size)

        val stream0 = ByteArrayInputStream(sentChunks[0])
        val pkt0 = ProtobufWire.parseFields(ProtobufWire.readFramedMessage(stream0))
        assertEquals(-1, stream0.read()) // Single frame only (no RemoteImeKeyInject frame)
        assertEquals(false, pkt0.any { it.number == 20 })
        val batch0 = pkt0.first { it.number == 21 }.asMessage()
        assertEquals(0L, batch0.first { it.number == 1 }.varint)
        assertEquals(0L, batch0.first { it.number == 2 }.varint)
        val obj0 = batch0.first { it.number == 3 }.asMessage().first { it.number == 2 }.asMessage()
        assertEquals(3L, obj0.first { it.number == 1 }.varint)
        assertEquals(3L, obj0.first { it.number == 2 }.varint)
        assertEquals("bts", obj0.first { it.number == 3 }.asString())

        val pkt1 = ProtobufWire.parseFields(ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[1])))
        val batch1 = pkt1.first { it.number == 21 }.asMessage()
        assertEquals(0L, batch1.first { it.number == 1 }.varint)
        assertEquals(0L, batch1.first { it.number == 2 }.varint)
        val obj1 = batch1.first { it.number == 3 }.asMessage().first { it.number == 2 }.asMessage()
        assertEquals(3L, obj1.first { it.number == 1 }.varint)
        assertEquals(3L, obj1.first { it.number == 2 }.varint)
        assertEquals("오징어", obj1.first { it.number == 3 }.asString())

        sentChunks.clear()
        val initShow = ProtobufWire.encode {
            writeMessageField(20) {
                writeMessageField(1) {
                    writeStringField(12, "com.android.vending")
                }
            }
            writeMessageField(22) {
                writeMessageField(2) {
                    writeVarintField(1, 4)
                    writeVarintField(5, 524289)
                    writeStringField(6, "Search Google Play")
                }
            }
        }
        val batchAck = ProtobufWire.encode {
            writeMessageField(21) {
                writeVarintField(1, 2) // fallback imeCounter = 2
                writeVarintField(2, 9) // must not override show_request fieldCounter = 4
            }
        }

        kotlinx.coroutines.runBlocking {
            client.applyIncomingFieldsForTest(ProtobufWire.parseFields(initShow))
            client.applyIncomingFieldsForTest(ProtobufWire.parseFields(batchAck))
            client.sendKeyboardChars("가나")
            client.sendKeyboardBackspace(1)
            client.sendText("", replaceExisting = true, submitWithEnter = false)
            client.applyIncomingFieldsForTest(
                ProtobufWire.parseFields(
                    ProtobufWire.encode {
                        writeMessageField(2) {
                            writeVarintField(1, 639)
                        }
                    }
                )
            )
        }
        // "가나" (single RemoteImeBatchEdit), DEL key, empty clear RemoteImeBatchEdit, RemoteSetActive reply
        assertEquals(4, sentChunks.size)

        val charsPkt = ProtobufWire.parseFields(
            ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[0]))
        )
        assertEquals(false, charsPkt.any { it.number == 20 })
        val charsBatch = charsPkt.first { it.number == 21 }.asMessage()
        assertEquals(2L, charsBatch.first { it.number == 1 }.varint)
        assertEquals(4L, charsBatch.first { it.number == 2 }.varint)
        val charsObj = charsBatch.first { it.number == 3 }.asMessage().first { it.number == 2 }.asMessage()
        assertEquals(2L, charsObj.first { it.number == 1 }.varint)
        assertEquals(2L, charsObj.first { it.number == 2 }.varint)
        assertEquals("가나", charsObj.first { it.number == 3 }.asString())

        val delFrame = ProtobufWire.parseFields(
            ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[1]))
        ).first { it.number == 10 }.asMessage()
        assertEquals(TvKeyCodes.DEL.toLong(), delFrame.first { it.number == 1 }.varint)
        assertEquals(AndroidTvRemoteClient.DIRECTION_SHORT.toLong(), delFrame.first { it.number == 2 }.varint)

        val clearBatch = ProtobufWire.parseFields(
            ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[2]))
        ).first { it.number == 21 }.asMessage()
        val clearObj = clearBatch.first { it.number == 3 }.asMessage().first { it.number == 2 }.asMessage()
        assertEquals(0L, clearObj.first { it.number == 1 }.varint)
        assertEquals(0L, clearObj.first { it.number == 2 }.varint)
        assertEquals("", clearObj.first { it.number == 3 }.asString())

        val activeFrame = ProtobufWire.parseFields(
            ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[3]))
        ).first { it.number == 2 }.asMessage()
        assertEquals(AndroidTvRemoteClient.REMOTE_FEATURES.toLong(), activeFrame.first { it.number == 1 }.varint)
    }

    @Test
    fun `key inject app_info counter takes precedence over stale batch_edit and survives show request`() {
        val sentChunks = mutableListOf<ByteArray>()
        val client = AndroidTvRemoteClient(
            certStore = null,
            onForegroundAppChanged = {},
            onImeShowRequested = { _, _ -> },
            onVolumeChanged = { _, _, _ -> },
            onDisconnected = {}
        ).apply {
            testRawBytesSink = { bytes -> sentChunks.add(bytes) }
        }

        // Google TV / Sony / Xiaomi katniss pattern:
        // RemoteImeKeyInject carries app_info.counter=5 & counter_field=68 while batch_edit carries 1 & 3
        val keyInject = ProtobufWire.encode {
            writeMessageField(20) {
                writeMessageField(1) {
                    writeVarintField(1, 5)
                    writeStringField(12, "com.google.android.katniss")
                }
                writeMessageField(2) {
                    writeVarintField(1, 68)
                    writeVarintField(5, 1)
                }
            }
        }
        val staleBatchEdit = ProtobufWire.encode {
            writeMessageField(21) {
                writeVarintField(1, 1)
                writeVarintField(2, 3)
            }
        }
        val echoShowRequest = ProtobufWire.encode {
            writeMessageField(22) {
                writeMessageField(2) {
                    writeVarintField(1, 71)
                    writeStringField(2, "stranger")
                    writeVarintField(3, 8)
                    writeVarintField(4, 8)
                }
            }
        }

        kotlinx.coroutines.runBlocking {
            client.applyIncomingFieldsForTest(ProtobufWire.parseFields(keyInject))
            client.applyIncomingFieldsForTest(ProtobufWire.parseFields(staleBatchEdit))
            assertEquals(5, client.debugImeCounter)
            assertEquals(68, client.debugFieldCounter)
            assertTrue(client.debugHasKeyInjectImeCounter)
            assertTrue(client.debugHasKeyInjectFieldCounter)

            client.sendText("stranger", replaceExisting = true, submitWithEnter = false)
            client.applyIncomingFieldsForTest(ProtobufWire.parseFields(echoShowRequest))
            assertEquals(5, client.debugImeCounter) // Must NOT reset imeCounter to 0 on show_request
            assertEquals(71, client.debugFieldCounter)

            client.sendText("things", replaceExisting = true, submitWithEnter = true)
        }

        assertEquals(3, sentChunks.size) // "stranger", "things", ENTER

        val firstBatch = ProtobufWire.parseFields(
            ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[0]))
        ).first { it.number == 21 }.asMessage()
        assertEquals(5L, firstBatch.first { it.number == 1 }.varint)
        assertEquals(68L, firstBatch.first { it.number == 2 }.varint)

        val secondBatch = ProtobufWire.parseFields(
            ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[1]))
        ).first { it.number == 21 }.asMessage()
        assertEquals(5L, secondBatch.first { it.number == 1 }.varint)
        assertEquals(71L, secondBatch.first { it.number == 2 }.varint)

        val enterKey = ProtobufWire.parseFields(
            ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[2]))
        ).first { it.number == 10 }.asMessage()
        assertEquals(TvKeyCodes.ENTER.toLong(), enterKey.first { it.number == 1 }.varint)
    }

    @Test
    fun `switching to app with omitted zero counters clears stale counters from previous app`() {
        val client = AndroidTvRemoteClient(
            certStore = null,
            onForegroundAppChanged = {},
            onImeShowRequested = { _, _ -> },
            onVolumeChanged = { _, _, _ -> },
            onDisconnected = {}
        )

        val playStoreInject = ProtobufWire.encode {
            writeMessageField(20) {
                writeMessageField(1) {
                    writeVarintField(1, 1)
                    writeStringField(12, "com.android.vending")
                }
                writeMessageField(2) {
                    writeVarintField(1, 38)
                }
            }
        }
        val youtubeSwitch = ProtobufWire.encode {
            writeMessageField(20) {
                writeMessageField(1) {
                    writeStringField(12, "com.google.android.youtube.tv")
                }
            }
        }
        val subsequentBatch = ProtobufWire.encode {
            writeMessageField(21) {
                writeVarintField(1, 2)
                writeVarintField(2, 4)
            }
        }

        kotlinx.coroutines.runBlocking {
            client.applyIncomingFieldsForTest(ProtobufWire.parseFields(playStoreInject))
            assertEquals(1, client.debugImeCounter)
            assertEquals(38, client.debugFieldCounter)

            client.applyIncomingFieldsForTest(ProtobufWire.parseFields(youtubeSwitch))
            assertEquals(false, client.isImeFieldActive)
            assertEquals(0, client.debugImeCounter)
            assertEquals(0, client.debugFieldCounter)
            assertEquals(0, client.resolvedImeFieldCounter())
            assertEquals(false, client.debugHasKeyInjectImeCounter)
            assertEquals(false, client.debugHasKeyInjectFieldCounter)

            client.applyIncomingFieldsForTest(ProtobufWire.parseFields(subsequentBatch))
            assertEquals(2, client.debugImeCounter)
            assertEquals(4, client.debugFieldCounter)
        }
    }

    @Test
    fun `sendKeyboardChars sends physical KEYCODE_SPACE for spacebar while batching non-space text`() {
        val sentChunks = mutableListOf<ByteArray>()
        val client = AndroidTvRemoteClient(
            certStore = null,
            onForegroundAppChanged = {},
            onImeShowRequested = { _, _ -> },
            onVolumeChanged = { _, _, _ -> },
            onDisconnected = {}
        ).apply {
            testRawBytesSink = { bytes -> sentChunks.add(bytes) }
        }

        kotlinx.coroutines.runBlocking {
            client.sendKeyboardChars(" ")
            client.sendKeyboardChars("bts mv")
        }

        // " " -> KEYCODE_SPACE(62); "bts mv" -> Batch("bts"), KEYCODE_SPACE(62), Batch("mv")
        assertEquals(4, sentChunks.size)

        val spaceOnly = ProtobufWire.parseFields(
            ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[0]))
        )
        assertEquals(false, spaceOnly.any { it.number == 21 })
        val spaceKeyMsg = spaceOnly.first { it.number == 10 }.asMessage()
        assertEquals(TvKeyCodes.SPACE.toLong(), spaceKeyMsg.first { it.number == 1 }.varint)
        assertEquals(AndroidTvRemoteClient.DIRECTION_SHORT.toLong(), spaceKeyMsg.first { it.number == 2 }.varint)

        val firstBatch = ProtobufWire.parseFields(
            ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[1]))
        ).first { it.number == 21 }.asMessage()
        assertEquals(
            "bts",
            firstBatch.first { it.number == 3 }.asMessage()
                .first { it.number == 2 }.asMessage()
                .first { it.number == 3 }.asString()
        )

        val midSpace = ProtobufWire.parseFields(
            ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[2]))
        ).first { it.number == 10 }.asMessage()
        assertEquals(TvKeyCodes.SPACE.toLong(), midSpace.first { it.number == 1 }.varint)

        val secondBatch = ProtobufWire.parseFields(
            ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[3]))
        ).first { it.number == 21 }.asMessage()
        assertEquals(
            "mv",
            secondBatch.first { it.number == 3 }.asMessage()
                .first { it.number == 2 }.asMessage()
                .first { it.number == 3 }.asString()
        )
        assertEquals(" bts mv", client.debugTrackedText)
    }

    @Test
    fun `tv keycodes and per-IME keymap map Q and hangul to physical keycodes`() {
        assertEquals(29, TvKeyCodes.KEYCODE_A)
        assertEquals(45, TvKeyCodes.KEYCODE_Q)
        assertEquals(54, TvKeyCodes.KEYCODE_Z)
        assertEquals(7, TvKeyCodes.KEYCODE_0)
        assertEquals(16, TvKeyCodes.KEYCODE_9)
        assertEquals(113, TvKeyCodes.CTRL_LEFT)
        assertEquals(59, TvKeyCodes.SHIFT_LEFT)
        assertEquals(204, TvKeyCodes.LANGUAGE_SWITCH)
        assertEquals(55, TvKeyCodes.COMMA)
        assertEquals(56, TvKeyCodes.PERIOD)

        assertEquals(
            com.example.data.model.TvImeType.GBOARD,
            com.example.data.model.TvImeKeycodeMap.resolveImeType("com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME")
        )
        assertEquals(
            com.example.data.model.TvImeType.GOOGLE_KOREAN,
            com.example.data.model.TvImeKeycodeMap.resolveImeType("com.google.android.inputmethod.korean/.KoreanIME")
        )
        assertEquals(
            com.example.data.model.TvImeType.SAMSUNG,
            com.example.data.model.TvImeKeycodeMap.resolveImeType("com.samsung.android.honeyboard/.service.HoneyBoardService")
        )
        assertEquals(
            com.example.data.model.PhysicalKeyAction(TvKeyCodes.LANGUAGE_SWITCH),
            com.example.data.model.TvImeKeycodeMap.resolveLanguageSwitchAction(com.example.data.model.TvImeType.GBOARD)
        )
        assertEquals(
            com.example.data.model.PhysicalKeyAction(TvKeyCodes.SPACE, shift = true),
            com.example.data.model.TvImeKeycodeMap.resolveLanguageSwitchAction(com.example.data.model.TvImeType.GOOGLE_KOREAN)
        )

        val qSpec = com.example.data.model.TvImeKeycodeMap.findSpecById("q")!!
        assertEquals("q", qSpec.labelFor(com.example.data.model.TvKeyboardLanguage.ENGLISH, shifted = false))
        assertEquals("Q", qSpec.labelFor(com.example.data.model.TvKeyboardLanguage.ENGLISH, shifted = true))
        assertEquals("ㅂ", qSpec.labelFor(com.example.data.model.TvKeyboardLanguage.KOREAN, shifted = false))
        assertEquals("ㅃ", qSpec.labelFor(com.example.data.model.TvKeyboardLanguage.KOREAN, shifted = true))

        assertEquals(
            com.example.data.model.PhysicalKeyAction(TvKeyCodes.KEYCODE_Q, shift = false),
            com.example.data.model.TvImeKeycodeMap.resolveKeyAction(
                com.example.data.model.TvImeType.GBOARD,
                com.example.data.model.TvKeyboardLanguage.KOREAN,
                qSpec,
                shifted = false
            )
        )
        assertEquals(
            com.example.data.model.PhysicalKeyAction(TvKeyCodes.KEYCODE_Q, shift = true),
            com.example.data.model.TvImeKeycodeMap.resolveKeyAction(
                com.example.data.model.TvImeType.GBOARD,
                com.example.data.model.TvKeyboardLanguage.KOREAN,
                qSpec,
                shifted = true
            )
        )

        // Single Korean jamo without doubled shift form (e.g., ㅁ) must NOT send spurious Shift
        val aSpec = com.example.data.model.TvImeKeycodeMap.findSpecById("a")!!
        assertEquals(
            com.example.data.model.PhysicalKeyAction(TvKeyCodes.KEYCODE_A, shift = false),
            com.example.data.model.TvImeKeycodeMap.resolveKeyAction(
                com.example.data.model.TvImeType.GBOARD,
                com.example.data.model.TvKeyboardLanguage.KOREAN,
                aSpec,
                shifted = true
            )
        )
        assertEquals(
            com.example.data.model.PhysicalKeyAction(TvKeyCodes.KEYCODE_A, shift = true),
            com.example.data.model.TvImeKeycodeMap.resolveKeyAction(
                com.example.data.model.TvImeType.GBOARD,
                com.example.data.model.TvKeyboardLanguage.ENGLISH,
                aSpec,
                shifted = true
            )
        )
    }

    @Test
    fun `sendText on empty string with replaceExisting and submitWithEnter emits clear batch edit before ENTER`() {
        val sentChunks = mutableListOf<ByteArray>()
        val client = AndroidTvRemoteClient(
            certStore = null,
            onForegroundAppChanged = {},
            onImeShowRequested = { _, _ -> },
            onVolumeChanged = { _, _, _ -> },
            onDisconnected = {}
        ).apply {
            testRawBytesSink = { bytes -> sentChunks.add(bytes) }
        }

        kotlinx.coroutines.runBlocking {
            client.sendText("", replaceExisting = true, submitWithEnter = true)
        }

        assertEquals(2, sentChunks.size)
        val clearBatch = ProtobufWire.parseFields(
            ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[0]))
        ).first { it.number == 21 }.asMessage()
        val clearObj = clearBatch.first { it.number == 3 }.asMessage().first { it.number == 2 }.asMessage()
        assertEquals("", clearObj.first { it.number == 3 }.asString())

        val enterKey = ProtobufWire.parseFields(
            ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[1]))
        ).first { it.number == 10 }.asMessage()
        assertEquals(TvKeyCodes.ENTER.toLong(), enterKey.first { it.number == 1 }.varint)
    }

    @Test
    fun `sendPhysicalKey with shift and selectAllAndDelete emit exact modifier key sequences`() {
        val sentChunks = mutableListOf<ByteArray>()
        val client = AndroidTvRemoteClient(
            certStore = null,
            onForegroundAppChanged = {},
            onImeShowRequested = { _, _ -> },
            onVolumeChanged = { _, _, _ -> },
            onDisconnected = {}
        ).apply {
            testRawBytesSink = { bytes -> sentChunks.add(bytes) }
        }

        kotlinx.coroutines.runBlocking {
            client.sendPhysicalKey(TvKeyCodes.KEYCODE_Q, shift = true)
        }

        assertEquals(4, sentChunks.size)
        fun keyAndDir(index: Int): Pair<Long, Long> {
            val msg = ProtobufWire.parseFields(
                ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[index]))
            ).first { it.number == 10 }.asMessage()
            return msg.first { it.number == 1 }.varint to msg.first { it.number == 2 }.varint
        }

        assertEquals(TvKeyCodes.SHIFT_LEFT.toLong() to AndroidTvRemoteClient.DIRECTION_START_LONG.toLong(), keyAndDir(0))
        assertEquals(TvKeyCodes.KEYCODE_Q.toLong() to AndroidTvRemoteClient.DIRECTION_START_LONG.toLong(), keyAndDir(1))
        assertEquals(TvKeyCodes.KEYCODE_Q.toLong() to AndroidTvRemoteClient.DIRECTION_END_LONG.toLong(), keyAndDir(2))
        assertEquals(TvKeyCodes.SHIFT_LEFT.toLong() to AndroidTvRemoteClient.DIRECTION_END_LONG.toLong(), keyAndDir(3))

        sentChunks.clear()
        kotlinx.coroutines.runBlocking {
            client.sendKeyboardChars("hi")
            sentChunks.clear()
            client.selectAllAndDelete()
        }

        assertEquals(5, sentChunks.size)
        assertEquals(TvKeyCodes.CTRL_LEFT.toLong() to AndroidTvRemoteClient.DIRECTION_START_LONG.toLong(), keyAndDir(0))
        assertEquals(TvKeyCodes.KEYCODE_A.toLong() to AndroidTvRemoteClient.DIRECTION_START_LONG.toLong(), keyAndDir(1))
        assertEquals(TvKeyCodes.KEYCODE_A.toLong() to AndroidTvRemoteClient.DIRECTION_END_LONG.toLong(), keyAndDir(2))
        assertEquals(TvKeyCodes.CTRL_LEFT.toLong() to AndroidTvRemoteClient.DIRECTION_END_LONG.toLong(), keyAndDir(3))
        assertEquals(TvKeyCodes.DEL.toLong() to AndroidTvRemoteClient.DIRECTION_SHORT.toLong(), keyAndDir(4))
        assertEquals("", client.debugTrackedText)
    }

    @Test
    fun `adb TV IME status parser and keycombination select-all-delete command builders behave accurately`() {
        val probeCmd = com.example.network.NetworkAdbClient.buildReadTvImeStatusShellCommand()
        assertTrue(probeCmd.contains("=$" + "CUR_SUB"))
        assertEquals(false, probeCmd.contains("\\$" + "CUR_SUB"))
        assertTrue(probeCmd.contains("mCurrentSubtype="))

        // Active subtype en_US overrides Korean system locale
        val enGboardDump = """
            CUR_IME=com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
            CUR_SUB=112233
            SYS_LOC=ko-KR
            ---CUR---
            mCurMethodId=com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
            mSystemLocale=ko_KR
            mCurrentSubtype=android.view.inputmethod.InputMethodSubtype@abc mSubtypeLocale=en_US
            ---SUB---
            ---SVC---
        """.trimIndent()
        val enStatus = com.example.network.NetworkAdbClient.parseTvImeStatus(enGboardDump)
        assertEquals(com.example.data.model.TvKeyboardLanguage.ENGLISH, enStatus.language)
        assertEquals(com.example.data.model.TvImeType.GBOARD, enStatus.imeType)
        assertEquals("en_US", enStatus.subtypeLocale)
        assertTrue(enStatus.isDetected)

        // Subtype lookup via ---SUB--- when mCurrentSubtype is omitted
        val koSubLookupDump = """
            CUR_IME=com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
            CUR_SUB=-987654
            SYS_LOC=en-US
            ---CUR---
            mSystemLocale=en_US
            ---SUB---
            mSubtypeHashCode=-987654
            locale=ko_KR
            ---SVC---
        """.trimIndent()
        val koStatus = com.example.network.NetworkAdbClient.parseTvImeStatus(koSubLookupDump)
        assertEquals(com.example.data.model.TvKeyboardLanguage.KOREAN, koStatus.language)
        assertEquals(com.example.data.model.TvImeType.GBOARD, koStatus.imeType)
        assertTrue(koStatus.isDetected)

        // Google Korean IME service flag hangul=false -> ENGLISH
        val koImeEnglishDump = """
            CUR_IME=com.google.android.inputmethod.korean/.KoreanIME
            CUR_SUB=
            SYS_LOC=ko-KR
            ---CUR---
            ---SUB---
            ---SVC---
            mHangul=false
        """.trimIndent()
        val koImeEn = com.example.network.NetworkAdbClient.parseTvImeStatus(koImeEnglishDump)
        assertEquals(com.example.data.model.TvKeyboardLanguage.ENGLISH, koImeEn.language)
        assertEquals(com.example.data.model.TvImeType.GOOGLE_KOREAN, koImeEn.imeType)
        assertTrue(koImeEn.isDetected)

        // Keycombination and Ctrl+A -> DEL command builders with RC=$? exit-code fallback check
        assertEquals(null, com.example.network.NetworkAdbClient.buildKeyCombinationShellCommand(TvKeyCodes.KEYCODE_A))
        assertEquals(
            "input keycombination 113 29 2>&1",
            com.example.network.NetworkAdbClient.buildKeyCombinationShellCommand(TvKeyCodes.CTRL_LEFT, TvKeyCodes.KEYCODE_A)
        )
        val selectAllDeleteCmd = com.example.network.NetworkAdbClient.buildSelectAllAndDeleteShellCommand()
        assertTrue(selectAllDeleteCmd.contains("input keycombination ${TvKeyCodes.CTRL_LEFT} ${TvKeyCodes.KEYCODE_A}"))
        assertTrue(selectAllDeleteCmd.contains("RC=$" + "?"))
        assertTrue(selectAllDeleteCmd.contains("Usage|Invalid"))
        assertTrue(selectAllDeleteCmd.contains("sleep 0.1"))
        assertTrue(selectAllDeleteCmd.contains("input keyevent ${TvKeyCodes.DEL} 2>&1"))
        val expectedFallback = "input keyevent 123 " + List(30) { "67" }.joinToString(" ")
        assertTrue(selectAllDeleteCmd.contains(expectedFallback))
    }

    @Test
    fun `real time phone keyboard edit mapper emits QWER English and Korean 2-bulsik transitions accurately`() {
        val ime = com.example.data.model.TvImeType.GBOARD

        // 1) English QWERTY typing + Backspace
        assertEquals(
            com.example.data.model.RealTimeTextEditResult(
                backspaceCount = 0,
                keyActions = listOf(com.example.data.model.PhysicalKeyAction(TvKeyCodes.KEYCODE_Q))
            ),
            com.example.data.model.TvImeKeycodeMap.computeRealTimeEditActions("", 0, 0, "q", ime)
        )
        assertEquals(
            com.example.data.model.RealTimeTextEditResult(
                backspaceCount = 0,
                keyActions = listOf(com.example.data.model.PhysicalKeyAction(TvKeyCodes.KEYCODE_Q, shift = true))
            ),
            com.example.data.model.TvImeKeycodeMap.computeRealTimeEditActions("", 0, 0, "Q", ime)
        )
        assertEquals(
            com.example.data.model.RealTimeTextEditResult(
                backspaceCount = 0,
                keyActions = listOf(
                    com.example.data.model.PhysicalKeyAction(TvKeyCodes.KEYCODE_W),
                    com.example.data.model.PhysicalKeyAction(TvKeyCodes.KEYCODE_E),
                    com.example.data.model.PhysicalKeyAction(TvKeyCodes.KEYCODE_R)
                )
            ),
            com.example.data.model.TvImeKeycodeMap.computeRealTimeEditActions("q", 1, 1, "qwer", ime)
        )
        assertEquals(
            com.example.data.model.RealTimeTextEditResult(backspaceCount = 1),
            com.example.data.model.TvImeKeycodeMap.computeRealTimeEditActions("qwer", 4, 4, "qwe", ime)
        )

        // 2) Korean 2-bulsik progressive composition (ㅂ -> 바 -> 반 -> 반값 -> 반갑사)
        assertEquals(
            com.example.data.model.RealTimeTextEditResult(
                backspaceCount = 0,
                keyActions = listOf(com.example.data.model.PhysicalKeyAction(TvKeyCodes.KEYCODE_Q))
            ),
            com.example.data.model.TvImeKeycodeMap.computeRealTimeEditActions("", 0, 0, "ㅂ", ime)
        )
        assertEquals(
            com.example.data.model.RealTimeTextEditResult(
                backspaceCount = 0,
                keyActions = listOf(com.example.data.model.PhysicalKeyAction(TvKeyCodes.KEYCODE_K))
            ),
            com.example.data.model.TvImeKeycodeMap.computeRealTimeEditActions("ㅂ", 1, 1, "바", ime)
        )
        assertEquals(
            com.example.data.model.RealTimeTextEditResult(
                backspaceCount = 0,
                keyActions = listOf(com.example.data.model.PhysicalKeyAction(TvKeyCodes.KEYCODE_S))
            ),
            com.example.data.model.TvImeKeycodeMap.computeRealTimeEditActions("바", 1, 1, "반", ime)
        )
        assertEquals(
            com.example.data.model.RealTimeTextEditResult(
                backspaceCount = 0,
                keyActions = listOf(com.example.data.model.PhysicalKeyAction(TvKeyCodes.KEYCODE_K))
            ),
            com.example.data.model.TvImeKeycodeMap.computeRealTimeEditActions("반값", 2, 2, "반갑사", ime)
        )

        // 3) Compound vowel addition & removal ("오" -> "왜", "과" -> "고")
        assertEquals(
            com.example.data.model.RealTimeTextEditResult(
                backspaceCount = 0,
                keyActions = listOf(com.example.data.model.PhysicalKeyAction(TvKeyCodes.KEYCODE_O))
            ),
            com.example.data.model.TvImeKeycodeMap.computeRealTimeEditActions("오", 1, 1, "왜", ime)
        )
        assertEquals(
            com.example.data.model.RealTimeTextEditResult(backspaceCount = 1),
            com.example.data.model.TvImeKeycodeMap.computeRealTimeEditActions("과", 1, 1, "고", ime)
        )

        // 4) Composing & committed Hangul Backspace ("한" -> "하", "안녕" -> "안")
        assertEquals(
            com.example.data.model.RealTimeTextEditResult(backspaceCount = 1),
            com.example.data.model.TvImeKeycodeMap.computeRealTimeEditActions("한", 1, 1, "하", ime)
        )
        assertEquals(
            com.example.data.model.RealTimeTextEditResult(backspaceCount = 1),
            com.example.data.model.TvImeKeycodeMap.computeRealTimeEditActions("안녕", 2, 2, "안", ime)
        )

        // 5) Doubled consonant shift transition ("ㄱ" -> "ㄲ") & no-op commit ("가" -> "가")
        assertEquals(
            com.example.data.model.RealTimeTextEditResult(
                backspaceCount = 1,
                keyActions = listOf(com.example.data.model.PhysicalKeyAction(TvKeyCodes.KEYCODE_R, shift = true))
            ),
            com.example.data.model.TvImeKeycodeMap.computeRealTimeEditActions("ㄱ", 1, 1, "ㄲ", ime)
        )
        assertEquals(
            com.example.data.model.RealTimeTextEditResult(),
            com.example.data.model.TvImeKeycodeMap.computeRealTimeEditActions("가", 1, 1, "가", ime)
        )

        // 6) Multi-char replacement uses codePointCount (2 syllables, NOT 4 jamo) + emoji single-DEL
        assertEquals(
            com.example.data.model.RealTimeTextEditResult(
                backspaceCount = 2,
                keyActions = listOf(com.example.data.model.PhysicalKeyAction(TvKeyCodes.KEYCODE_A))
            ),
            com.example.data.model.TvImeKeycodeMap.computeRealTimeEditActions("가나다", 0, 2, "ㅁ다", ime)
        )
        assertEquals(
            com.example.data.model.RealTimeTextEditResult(backspaceCount = 1),
            com.example.data.model.TvImeKeycodeMap.computeRealTimeEditActions("a😀", 3, 3, "a", ime)
        )

        // 7) Space, newline, digits & ASCII punctuation
        assertEquals(
            com.example.data.model.RealTimeTextEditResult(
                backspaceCount = 0,
                keyActions = listOf(
                    com.example.data.model.PhysicalKeyAction(TvKeyCodes.SPACE),
                    com.example.data.model.PhysicalKeyAction(TvKeyCodes.KEYCODE_1, shift = true),
                    com.example.data.model.PhysicalKeyAction(TvKeyCodes.SLASH, shift = true)
                )
            ),
            com.example.data.model.TvImeKeycodeMap.computeRealTimeEditActions("", 0, 0, " !?", ime)
        )
    }

    @Test
    fun `voice chunk accumulator and stream pipeline enforce 8KB to 20KB chunks with padded flush`() {
        val pipeline = com.example.network.VoiceStreamPipeline()
        val emitted = mutableListOf<Pair<Int, ByteArray>>()
        val onChunk: suspend (Int, ByteArray) -> Unit = { sid, chunk -> emitted.add(sid to chunk) }

        kotlinx.coroutines.runBlocking {
            pipeline.beginCapture(generation = 1)
            // 5 * 1600 = 8000 bytes (< 8192 minimum) remains in buffer even after activateSession
            repeat(5) { idx ->
                pipeline.pushFrame(1, ByteArray(1600) { (idx + 1).toByte() }, onChunk)
            }
            pipeline.activateSession(generation = 1, sessionId = 9, onChunk = onChunk)
            assertEquals(0, emitted.size)

            // 6th frame reaches 9600 bytes -> emits single 9600-byte chunk inside [8192, 20480]
            pipeline.pushFrame(1, ByteArray(1600) { 0x42 }, onChunk)
            assertEquals(1, emitted.size)
            assertEquals(9, emitted[0].first)
            assertEquals(9600, emitted[0].second.size)

            // Oversized 25,000-byte frame must split without emitting any <8192 slice -> [16808, 8192]
            emitted.clear()
            val largeFrame = ByteArray(25_000) { i -> ((i and 0x7F) + 1).toByte() }
            pipeline.pushFrame(1, largeFrame, onChunk)
            assertEquals(2, emitted.size)
            assertEquals(16808, emitted[0].second.size)
            assertEquals(8192, emitted[1].second.size)
            assertTrue(emitted.all { it.second.size in 8192..20480 && it.second.size % 2 == 0 })

            // Short tail (1600 bytes) is zero-padded up to 8192 bytes on finishAndFlush
            emitted.clear()
            val tail = ByteArray(1600) { 0x55 }
            pipeline.pushFrame(1, tail, onChunk)
            assertEquals(0, emitted.size)
            pipeline.finishAndFlush(sessionId = 9, onChunk = onChunk)
            assertEquals(1, emitted.size)
            assertEquals(8192, emitted[0].second.size)
            assertTrue(tail.contentEquals(emitted[0].second.copyOfRange(0, 1600)))
            assertTrue(emitted[0].second.copyOfRange(1600, 8192).all { it == 0.toByte() })

            // Stale frame generation after reset is ignored cleanly
            emitted.clear()
            pipeline.pushFrame(1, ByteArray(10_000) { 0x77 }, onChunk)
            assertEquals(0, emitted.size)
        }
    }

    @Test
    fun `remote client negotiates FEATURE_VOICE bitmask splits oversized payloads and decodes RemoteError`() {
        val errorInfos = mutableListOf<com.example.network.AndroidTvRemoteClient.RemoteErrorInfo>()
        val sentFrames = mutableListOf<ByteArray>()
        val client = com.example.network.AndroidTvRemoteClient(
            certStore = null,
            onForegroundAppChanged = {},
            onImeShowRequested = { _, _ -> },
            onVolumeChanged = { _, _, _ -> },
            onRemoteErrorInfo = { errorInfos.add(it) },
            onDisconnected = {}
        ).apply {
            testRawBytesSink = { sentFrames.add(it) }
        }

        // Standard Google TV feature flags (623 = PING|KEY|IME|VOICE(8)|POWER(32)|VOLUME(64)|APP_LINK(512))
        val voiceCapableConfig = ProtobufWire.encode {
            writeMessageField(1) {
                writeVarintField(1, 623)
            }
        }
        // TV without FEATURE_VOICE bit (623 & ~8 = 615)
        val noVoiceConfig = ProtobufWire.encode {
            writeMessageField(1) {
                writeVarintField(1, 615)
            }
        }
        // RemoteError wrapping RemoteVoiceBegin(30)
        val voiceBeginErr = ProtobufWire.encode {
            writeMessageField(3) {
                writeBoolField(1, true)
                writeMessageField(2) {
                    writeMessageField(30) {
                        writeVarintField(1, 5)
                    }
                }
            }
        }

        kotlinx.coroutines.runBlocking {
            assertEquals(null, client.isVoiceSupportedByTv)
            client.applyIncomingFieldsForTest(ProtobufWire.parseFields(voiceCapableConfig))
            assertEquals(623, client.tvFeatures)
            assertEquals(true, client.isVoiceSupportedByTv)

            client.applyIncomingFieldsForTest(ProtobufWire.parseFields(noVoiceConfig))
            assertEquals(615, client.tvFeatures)
            assertEquals(false, client.isVoiceSupportedByTv)

            client.applyIncomingFieldsForTest(ProtobufWire.parseFields(voiceBeginErr))
            client.sendVoicePayload(7, ByteArray(25_000) { 0x1A })
        }

        assertEquals(1, errorInfos.size)
        assertEquals(true, errorInfos[0].value)
        assertEquals(30, errorInfos[0].offendingFieldNumber)
        assertTrue(errorInfos[0].detail.contains("field=30"))

        assertEquals(2, sentFrames.size)
        val p0 = ProtobufWire.parseFields(ProtobufWire.readFramedMessage(ByteArrayInputStream(sentFrames[0])))
            .first { it.number == 31 }.asMessage()
        val p1 = ProtobufWire.parseFields(ProtobufWire.readFramedMessage(ByteArrayInputStream(sentFrames[1])))
            .first { it.number == 31 }.asMessage()
        assertEquals(16808, p0.first { it.number == 2 }.bytes.size)
        assertEquals(8192, p1.first { it.number == 2 }.bytes.size)
    }

    @Test
    fun `remote client negotiates supported features intersects bitmask and emits voice ack and start callbacks`() {
        assertEquals(639, AndroidTvRemoteClient.computeNegotiatedFeatures(null))
        assertEquals(639, AndroidTvRemoteClient.computeNegotiatedFeatures(0))
        assertEquals(623, AndroidTvRemoteClient.computeNegotiatedFeatures(623))
        assertEquals(615, AndroidTvRemoteClient.computeNegotiatedFeatures(615))

        val ackedSessions = mutableListOf<Int>()
        val startStates = mutableListOf<Boolean>()
        val sentFrames = mutableListOf<ByteArray>()
        val client = AndroidTvRemoteClient(
            certStore = null,
            onForegroundAppChanged = {},
            onImeShowRequested = { _, _ -> },
            onVolumeChanged = { _, _, _ -> },
            onVoicePayloadAck = { ackedSessions.add(it) },
            onTvStartChanged = { startStates.add(it) },
            onDisconnected = {}
        ).apply {
            testRawBytesSink = { sentFrames.add(it) }
        }

        val incomingCfg = ProtobufWire.encode {
            writeMessageField(1) { writeVarintField(1, 615) }
        }
        val setActive = ProtobufWire.encode {
            writeMessageField(2) { writeVarintField(1, 615) }
        }
        val voiceAck = ProtobufWire.encode {
            writeMessageField(31) { writeVarintField(1, 77) }
        }
        val remoteStartTrue = ProtobufWire.encode {
            writeMessageField(40) { writeBoolField(1, true) }
        }
        val remoteStartFalse = ProtobufWire.encode {
            writeMessageField(40) { writeBoolField(1, false) }
        }

        kotlinx.coroutines.runBlocking {
            client.applyIncomingFieldsForTest(ProtobufWire.parseFields(incomingCfg))
            client.applyIncomingFieldsForTest(ProtobufWire.parseFields(setActive))
            client.applyIncomingFieldsForTest(ProtobufWire.parseFields(voiceAck))
            client.applyIncomingFieldsForTest(ProtobufWire.parseFields(remoteStartTrue))
            client.applyIncomingFieldsForTest(ProtobufWire.parseFields(remoteStartFalse))
        }

        assertEquals(1, sentFrames.size)
        val activeReplyMsg = ProtobufWire.parseFields(ProtobufWire.readFramedMessage(ByteArrayInputStream(sentFrames[0])))
            .first { it.number == 2 }.asMessage()
        assertEquals(615L, activeReplyMsg.first { it.number == 1 }.varint)
        assertEquals(listOf(77), ackedSessions)
        assertEquals(listOf(true, false), startStates)
        assertEquals(219, TvKeyCodes.ASSIST)
    }

    @Test
    fun `adb assistant launch and global search builders support Korean UTF-8 and sanitize control chars`() {
        val launchCmd = com.example.network.NetworkAdbClient.buildGoogleAssistantLaunchShellCommand()
        assertTrue(launchCmd.contains("android.intent.action.ASSIST"))
        assertTrue(launchCmd.contains("input keyevent ${TvKeyCodes.VOICE_ASSIST}"))
        assertTrue(launchCmd.contains("input keyevent ${TvKeyCodes.ASSIST}"))

        assertEquals(null, com.example.network.NetworkAdbClient.buildGoogleAssistantQueryShellCommand("  \n\r "))
        val queryCmd = com.example.network.NetworkAdbClient.buildGoogleAssistantQueryShellCommand("오늘 '날씨'\n알려줘")!!
        assertTrue(queryCmd.contains("android.search.action.GLOBAL_SEARCH"))
        assertTrue(queryCmd.contains("android.intent.action.SEARCH"))
        assertTrue(queryCmd.contains("--es query '오늘 '\\''날씨'\\'' 알려줘'"))
        assertEquals(false, queryCmd.contains("\n"))
    }

    @Test
    fun `voice streamer pcm16 peak amplitude detects silence vs speech safely`() {
        assertEquals(0, com.example.network.TvVoiceStreamer.computePcm16PeakAmplitude(ByteArray(0)))
        // 16-bit LE +257 -> below threshold (600)
        val quiet = byteArrayOf(0x01, 0x01, 0x05) // includes trailing odd byte edge case
        assertEquals(257, com.example.network.TvVoiceStreamer.computePcm16PeakAmplitude(quiet))
        // 16-bit LE -32768 -> 32768 >= threshold
        val loudNeg = byteArrayOf(0x00, 0x80.toByte())
        assertEquals(32768, com.example.network.TvVoiceStreamer.computePcm16PeakAmplitude(loudNeg))
    }
}
