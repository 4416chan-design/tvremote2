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
    fun `adb keyboard command builder formats ADB_CLEAR_TEXT ADB_INPUT_TEXT and Korean ADB_INPUT_B64`() {
        assertEquals(
            "https://github.com/senzhk/ADBKeyBoard/releases/download/v2.4-dev/keyboardservice-debug.apk",
            com.example.network.NetworkAdbClient.ADB_KEYBOARD_APK_URL
        )
        assertEquals(null, com.example.network.NetworkAdbClient.buildAdbKeyboardSendCommand("", clearExisting = false, submitWithEnter = false))
        assertEquals("input keyevent 66 2>&1", com.example.network.NetworkAdbClient.buildAdbKeyboardSendCommand("", clearExisting = false, submitWithEnter = true))

        val asciiCmd = com.example.network.NetworkAdbClient.buildAdbKeyboardSendCommand(
            text = "it's bts",
            clearExisting = true,
            submitWithEnter = true
        )!!
        assertTrue(asciiCmd.contains("am broadcast -a ADB_CLEAR_TEXT 2>&1"))
        assertTrue(asciiCmd.contains("am broadcast -a ADB_INPUT_TEXT --es msg 'it'\\''s bts' 2>&1"))
        assertTrue(asciiCmd.contains("input keyevent 66 2>&1"))
        assertTrue(asciiCmd.contains("ime set \"\$PREV_IME\""))

        val koCmd = com.example.network.NetworkAdbClient.buildAdbKeyboardSendCommand(
            text = "유튜브 검색",
            clearExisting = true,
            submitWithEnter = true
        )!!
        val expectedB64 = com.example.network.AdbKeyManager.encodeBase64("유튜브 검색".toByteArray(Charsets.UTF_8))
        assertTrue(koCmd.contains("am broadcast -a ADB_CLEAR_TEXT 2>&1"))
        assertTrue(koCmd.contains("am broadcast -a ADB_INPUT_B64 --es msg '$expectedB64' 2>&1"))
        assertTrue(koCmd.contains("input keyevent 66 2>&1"))

        val clearOnly = com.example.network.NetworkAdbClient.buildAdbKeyboardSendCommand(
            text = "",
            clearExisting = true,
            submitWithEnter = false
        )!!
        assertTrue(clearOnly.contains("am broadcast -a ADB_CLEAR_TEXT 2>&1"))
        assertEquals(false, clearOnly.contains("ADB_INPUT_TEXT"))
        assertEquals(false, clearOnly.contains("input keyevent 66"))

        val clearAndEnter = com.example.network.NetworkAdbClient.buildAdbKeyboardSendCommand(
            text = "",
            clearExisting = true,
            submitWithEnter = true
        )!!
        assertTrue(clearAndEnter.contains("am broadcast -a ADB_CLEAR_TEXT 2>&1; sleep 0.1; input keyevent 66 2>&1"))
        assertEquals(false, clearAndEnter.contains("ADB_INPUT_TEXT"))
        assertEquals(false, clearAndEnter.contains("ADB_INPUT_B64"))
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
    fun `adb sync file transfer and pm install succeed over loopback tcp mux`() {
        val tempDir = java.io.File.createTempFile("adb_sync_test", "").apply {
            delete()
            mkdirs()
        }
        val server = java.net.ServerSocket(0)
        val receivedSyncBytes = java.io.ByteArrayOutputStream()
        var capturedInstallShell = ""
        val serverReady = java.util.concurrent.CountDownLatch(1)

        val serverThread = Thread {
            serverReady.countDown()
            val sock = server.accept()
            sock.soTimeout = 5_000
            val inp = sock.getInputStream()
            val out = sock.getOutputStream()

            fun readFully(n: Int): ByteArray {
                val buf = ByteArray(n)
                var off = 0
                while (off < n) {
                    val r = inp.read(buf, off, n - off)
                    if (r < 0) throw java.io.EOFException()
                    off += r
                }
                return buf
            }

            fun writePkt(cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) {
                val sum = data.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) }
                val hdr = java.nio.ByteBuffer.allocate(24).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                    putInt(cmd); putInt(a0); putInt(a1); putInt(data.size); putInt(sum); putInt(cmd.inv())
                }.array()
                out.write(hdr)
                if (data.isNotEmpty()) out.write(data)
                out.flush()
            }

            val aCnxn = 0x4e584e43
            val aOpen = 0x4e45504f
            val aOkay = 0x59414b4f
            val aClse = 0x45534c43
            val aWrte = 0x45545257
            var activeService = ""
            var peerStreamId = 0
            val syncAccumulator = java.io.ByteArrayOutputStream()
            var syncOffset = 0
            var parsedSpec = false
            var doneReceived = false

            fun tryParseSyncBuffer() {
                val bytes = syncAccumulator.toByteArray()
                while (true) {
                    if (bytes.size - syncOffset < 8) return
                    val tag = String(bytes, syncOffset, 4, Charsets.US_ASCII)
                    val lenOrMtime = java.nio.ByteBuffer.wrap(bytes, syncOffset + 4, 4)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                    when (tag) {
                        "SEND" -> {
                            if (bytes.size - syncOffset < 8 + lenOrMtime) return
                            parsedSpec = true
                            syncOffset += 8 + lenOrMtime
                        }
                        "DATA" -> {
                            if (bytes.size - syncOffset < 8 + lenOrMtime) return
                            receivedSyncBytes.write(bytes, syncOffset + 8, lenOrMtime)
                            syncOffset += 8 + lenOrMtime
                        }
                        "DONE" -> {
                            doneReceived = true
                            syncOffset += 8
                            val okReply = "OKAY\u0000\u0000\u0000\u0000".toByteArray(Charsets.US_ASCII)
                            writePkt(aWrte, 99, peerStreamId, okReply)
                        }
                        "QUIT" -> {
                            syncOffset += 8
                            writePkt(aClse, 99, peerStreamId)
                        }
                        else -> return
                    }
                }
            }

            try {
                while (!sock.isClosed) {
                    val hdr = try {
                        readFully(24)
                    } catch (_: Throwable) {
                        break
                    }
                    val bb = java.nio.ByteBuffer.wrap(hdr).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    val cmd = bb.getInt()
                    val a0 = bb.getInt()
                    val a1 = bb.getInt()
                    val len = bb.getInt()
                    val payload = if (len > 0) readFully(len) else ByteArray(0)

                    when (cmd) {
                        aCnxn -> writePkt(aCnxn, 0x01000001, 64 * 1024, "device::\u0000".toByteArray())
                        aOpen -> {
                            val svc = String(payload, 0, (payload.size - 1).coerceAtLeast(0), Charsets.UTF_8)
                            activeService = svc
                            peerStreamId = a0
                            writePkt(aOkay, 99, peerStreamId)
                            if (svc.startsWith("shell:")) {
                                capturedInstallShell = svc.removePrefix("shell:")
                                writePkt(aWrte, 99, peerStreamId, "INSTALL=OK|Success\n".toByteArray())
                                writePkt(aClse, 99, peerStreamId)
                            }
                        }
                        aWrte -> {
                            writePkt(aOkay, 99, a0)
                            if (activeService == "sync:") {
                                syncAccumulator.write(payload)
                                tryParseSyncBuffer()
                            }
                        }
                    }
                }
            } finally {
                runCatching { sock.close() }
            }
        }.apply {
            isDaemon = true
            start()
        }

        try {
            serverReady.await()
            val client = com.example.network.NetworkAdbClient(com.example.network.AdbKeyManager(tempDir))
            val fakeApk = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4) +
                ByteArray(70_000) { (it % 251).toByte() } // >64KB to also exercise chunking!

            kotlinx.coroutines.runBlocking {
                val badRes = client.installApkBytesOnTv(byteArrayOf(1, 2, 3, 4))
                assertTrue(badRes.isFailure)

                val connected = client.connect("127.0.0.1", server.localPort)
                assertTrue(
                    "Expected connect success, state=${client.adbState.value}, status=${client.adbStatusDetail.value}",
                    connected
                )
                val installRes = client.installApkBytesOnTv(fakeApk)
                assertTrue("Expected install success, got ${installRes.exceptionOrNull()}", installRes.isSuccess)
                assertTrue(client.hasAdbKeyboardIme()) // cached true immediately after install
                client.disconnect()
            }

            assertTrue(fakeApk.contentEquals(receivedSyncBytes.toByteArray()))
            assertTrue(capturedInstallShell.contains("pm install -r -d /data/local/tmp/keyboardservice-debug.apk"))
            assertTrue(capturedInstallShell.contains("ime enable com.android.adbkeyboard/.AdbIME"))
        } finally {
            server.close()
            serverThread.join(1_000)
            tempDir.deleteRecursively()
        }
    }
}
