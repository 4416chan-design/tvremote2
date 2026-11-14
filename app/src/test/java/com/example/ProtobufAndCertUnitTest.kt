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
    fun `ime batch edit payload uses insert one full text and cursor at len minus one`() {
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
        assertEquals(5L, imeObj.first { it.number == 1 }.varint)
        assertEquals(5L, imeObj.first { it.number == 2 }.varint)
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
    fun `sendText and sendKeyboardChars emit dual RemoteImeKeyInject and insert-1 RemoteImeBatchEdit`() {
        val sentChunks = mutableListOf<ByteArray>()
        val client = com.example.network.AndroidTvRemoteClient(
            certStore = null,
            onForegroundAppChanged = {},
            onImeShowRequested = { _, _ -> },
            onVolumeChanged = { _, _, _ -> },
            onDisconnected = {}
        ).apply {
            testRawBytesSink = { bytes -> sentChunks.add(bytes) }
        }

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
                writeVarintField(1, 2) // syncedImeCounter = 2
                writeVarintField(2, 4) // syncedFieldCounter = 4
            }
        }

        kotlinx.coroutines.runBlocking {
            client.applyIncomingFieldsForTest(ProtobufWire.parseFields(initShow))
            client.applyIncomingFieldsForTest(ProtobufWire.parseFields(batchAck))
            client.sendText("오징어", replaceExisting = true, submitWithEnter = false)
        }

        assertEquals(1, sentChunks.size)
        val input = ByteArrayInputStream(sentChunks.first())
        val frame1 = ProtobufWire.parseFields(ProtobufWire.readFramedMessage(input))
        val frame2 = ProtobufWire.parseFields(ProtobufWire.readFramedMessage(input))

        val keyInject = frame1.first { it.number == 20 }.asMessage()
        val appInfo = keyInject.first { it.number == 1 }.asMessage()
        assertEquals("com.android.vending", appInfo.first { it.number == 12 }.asString())
        val status = keyInject.first { it.number == 2 }.asMessage()
        assertEquals(4L, status.first { it.number == 1 }.varint)
        assertEquals("오징어", status.first { it.number == 2 }.asString())
        assertEquals(3L, status.first { it.number == 3 }.varint)
        assertEquals(3L, status.first { it.number == 4 }.varint)
        assertEquals(524289L, status.first { it.number == 5 }.varint)
        assertEquals("Search Google Play", status.first { it.number == 6 }.asString())

        val batchEdit = frame2.first { it.number == 21 }.asMessage()
        assertEquals(2L, batchEdit.first { it.number == 1 }.varint)
        assertEquals(4L, batchEdit.first { it.number == 2 }.varint)
        val editInfo = batchEdit.first { it.number == 3 }.asMessage()
        assertEquals(1L, editInfo.first { it.number == 1 }.varint)
        val imeObj = editInfo.first { it.number == 2 }.asMessage()
        assertEquals(3L, imeObj.first { it.number == 1 }.varint)
        assertEquals(3L, imeObj.first { it.number == 2 }.varint)
        assertEquals("오징어", imeObj.first { it.number == 3 }.asString())

        sentChunks.clear()
        kotlinx.coroutines.runBlocking {
            client.sendKeyboardChars("가나")
            client.sendKeyboardBackspace(1)
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
        // "가" (dual frame), "나" (dual frame), DEL key, RemoteSetActive reply
        assertEquals(4, sentChunks.size)
        val delFrame = ProtobufWire.parseFields(
            ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[2]))
        ).first { it.number == 10 }.asMessage()
        assertEquals(com.example.data.model.TvKeyCodes.DEL.toLong(), delFrame.first { it.number == 1 }.varint)
        assertEquals(AndroidTvRemoteClient.DIRECTION_SHORT.toLong(), delFrame.first { it.number == 2 }.varint)

        val activeFrame = ProtobufWire.parseFields(
            ProtobufWire.readFramedMessage(ByteArrayInputStream(sentChunks[3]))
        ).first { it.number == 2 }.asMessage()
        assertEquals(AndroidTvRemoteClient.REMOTE_FEATURES.toLong(), activeFrame.first { it.number == 1 }.varint)
    }
}
