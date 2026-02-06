package org.drewcarlson.fraggle.db

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ExposedChatHistoryStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var database: Database
    private lateinit var store: ExposedChatHistoryStore

    @BeforeEach
    fun setup() {
        val dbPath = tempDir.resolve("test.db")
        database = Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")
        transaction(database) {
            val conn = this.connection.connection as java.sql.Connection
            conn.createStatement().use { it.execute("PRAGMA foreign_keys=ON;") }
        }
        MigrationRunner(database).run()
        store = ExposedChatHistoryStore(database)
    }

    @Nested
    inner class ChatTests {

        @Test
        fun `getOrCreateChat creates new chat`() {
            val chat = store.getOrCreateChat(
                externalId = "signal:+1234567890",
                platform = "signal",
                name = "Test User",
            )

            assertEquals("signal:+1234567890", chat.externalId)
            assertEquals("signal", chat.platform)
            assertEquals("Test User", chat.name)
            assertEquals(false, chat.isGroup)
            assertTrue(chat.id > 0)
        }

        @Test
        fun `getOrCreateChat returns existing chat`() {
            val first = store.getOrCreateChat(
                externalId = "signal:+1234567890",
                platform = "signal",
                name = "Test User",
            )
            val second = store.getOrCreateChat(
                externalId = "signal:+1234567890",
                platform = "signal",
            )

            assertEquals(first.id, second.id)
            assertEquals(first.externalId, second.externalId)
        }

        @Test
        fun `getOrCreateChat updates lastActiveAt on revisit`() {
            val first = store.getOrCreateChat(
                externalId = "signal:+1234567890",
                platform = "signal",
            )

            // Small delay to ensure different timestamp
            Thread.sleep(10)

            val second = store.getOrCreateChat(
                externalId = "signal:+1234567890",
                platform = "signal",
            )

            assertTrue(second.lastActiveAt >= first.lastActiveAt)
        }

        @Test
        fun `getOrCreateChat updates name on revisit`() {
            store.getOrCreateChat(
                externalId = "signal:+1234567890",
                platform = "signal",
                name = "Old Name",
            )
            val updated = store.getOrCreateChat(
                externalId = "signal:+1234567890",
                platform = "signal",
                name = "New Name",
            )

            assertEquals("New Name", updated.name)
        }

        @Test
        fun `getOrCreateChat creates group chat`() {
            val chat = store.getOrCreateChat(
                externalId = "discord:12345",
                platform = "discord",
                name = "General",
                isGroup = true,
            )

            assertEquals(true, chat.isGroup)
            assertEquals("General", chat.name)
        }

        @Test
        fun `getChat returns null for unknown chat`() {
            val chat = store.getChat("nonexistent")
            assertNull(chat)
        }

        @Test
        fun `getChat returns existing chat`() {
            store.getOrCreateChat(
                externalId = "signal:+1234567890",
                platform = "signal",
                name = "Test",
            )

            val chat = store.getChat("signal:+1234567890")
            assertNotNull(chat)
            assertEquals("signal", chat.platform)
            assertEquals("Test", chat.name)
        }

        @Test
        fun `getChatById returns chat by internal ID`() {
            val created = store.getOrCreateChat(
                externalId = "signal:+1234567890",
                platform = "signal",
            )

            val fetched = store.getChatById(created.id)
            assertNotNull(fetched)
            assertEquals(created.externalId, fetched.externalId)
        }

        @Test
        fun `getChatById returns null for unknown ID`() {
            assertNull(store.getChatById(99999))
        }

        @Test
        fun `listChats returns chats ordered by lastActiveAt descending`() {
            store.getOrCreateChat("signal:a", "signal")
            Thread.sleep(10)
            store.getOrCreateChat("signal:b", "signal")
            Thread.sleep(10)
            store.getOrCreateChat("signal:c", "signal")

            val chats = store.listChats()

            assertEquals(3, chats.size)
            assertEquals("signal:c", chats[0].externalId)
            assertEquals("signal:b", chats[1].externalId)
            assertEquals("signal:a", chats[2].externalId)
        }

        @Test
        fun `listChats respects limit and offset`() {
            store.getOrCreateChat("signal:a", "signal")
            Thread.sleep(10)
            store.getOrCreateChat("signal:b", "signal")
            Thread.sleep(10)
            store.getOrCreateChat("signal:c", "signal")

            val page = store.listChats(limit = 1, offset = 1)

            assertEquals(1, page.size)
            assertEquals("signal:b", page[0].externalId)
        }

        @Test
        fun `updateChatName updates name`() {
            store.getOrCreateChat("signal:+1234567890", "signal", name = "Old")

            store.updateChatName("signal:+1234567890", "New Name")

            val chat = store.getChat("signal:+1234567890")
            assertEquals("New Name", chat?.name)
        }

        @Test
        fun `countChats returns correct count`() {
            assertEquals(0, store.countChats())

            store.getOrCreateChat("signal:a", "signal")
            store.getOrCreateChat("signal:b", "signal")

            assertEquals(2, store.countChats())
        }
    }

    @Nested
    inner class MessageTests {

        private lateinit var chat: ChatRecord

        @BeforeEach
        fun createChat() {
            chat = store.getOrCreateChat("signal:+1234567890", "signal")
        }

        @Test
        fun `recordMessage persists message metadata`() {
            val now = Clock.System.now()
            val recorded = store.recordMessage(MessageRecord(
                chatId = chat.id,
                externalId = "msg-001",
                senderId = "user-123",
                senderName = "Alice",
                senderIsBot = false,
                contentType = MessageContentType.TEXT,
                direction = MessageDirection.INCOMING,
                timestamp = now,
            ))

            assertTrue(recorded.id > 0)
            assertEquals(chat.id, recorded.chatId)
            assertEquals("msg-001", recorded.externalId)
            assertEquals("user-123", recorded.senderId)
            assertEquals("Alice", recorded.senderName)
            assertEquals(false, recorded.senderIsBot)
            assertEquals(MessageContentType.TEXT, recorded.contentType)
            assertEquals(MessageDirection.INCOMING, recorded.direction)
        }

        @Test
        fun `recordMessage with processing duration`() {
            val duration = 1500.milliseconds
            store.recordMessage(MessageRecord(
                chatId = chat.id,
                senderId = "fraggle",
                senderIsBot = true,
                contentType = MessageContentType.TEXT,
                direction = MessageDirection.OUTGOING,
                timestamp = Clock.System.now(),
                processingDuration = duration,
            ))

            val messages = store.getMessages(chat.id)
            assertEquals(1, messages.size)
            assertEquals(duration, messages[0].processingDuration)
        }

        @Test
        fun `recordMessage with null processing duration`() {
            store.recordMessage(MessageRecord(
                chatId = chat.id,
                senderId = "user-123",
                contentType = MessageContentType.TEXT,
                direction = MessageDirection.INCOMING,
                timestamp = Clock.System.now(),
                processingDuration = null,
            ))

            val messages = store.getMessages(chat.id)
            assertNull(messages[0].processingDuration)
        }

        @Test
        fun `recordMessage with different content types`() {
            for (type in MessageContentType.entries) {
                store.recordMessage(MessageRecord(
                    chatId = chat.id,
                    senderId = "user-123",
                    contentType = type,
                    direction = MessageDirection.INCOMING,
                    timestamp = Clock.System.now(),
                ))
            }

            val messages = store.getMessages(chat.id, limit = 100)
            assertEquals(MessageContentType.entries.size, messages.size)
            val recordedTypes = messages.map { it.contentType }.toSet()
            assertEquals(MessageContentType.entries.toSet(), recordedTypes)
        }

        @Test
        fun `recordMessage updates chat lastActiveAt`() {
            val chatBefore = store.getChat(chat.externalId)!!
            Thread.sleep(10)

            store.recordMessage(MessageRecord(
                chatId = chat.id,
                senderId = "user-123",
                contentType = MessageContentType.TEXT,
                direction = MessageDirection.INCOMING,
                timestamp = Clock.System.now(),
            ))

            val chatAfter = store.getChat(chat.externalId)!!
            assertTrue(chatAfter.lastActiveAt >= chatBefore.lastActiveAt)
        }

        @Test
        fun `getMessages returns messages ordered by timestamp descending`() {
            val now = Clock.System.now()
            store.recordMessage(MessageRecord(
                chatId = chat.id, senderId = "user", contentType = MessageContentType.TEXT,
                direction = MessageDirection.INCOMING, timestamp = now,
            ))
            store.recordMessage(MessageRecord(
                chatId = chat.id, senderId = "fraggle", contentType = MessageContentType.TEXT,
                direction = MessageDirection.OUTGOING, timestamp = now + 1.seconds,
            ))
            store.recordMessage(MessageRecord(
                chatId = chat.id, senderId = "user", contentType = MessageContentType.IMAGE,
                direction = MessageDirection.INCOMING, timestamp = now + 2.seconds,
            ))

            val messages = store.getMessages(chat.id)

            assertEquals(3, messages.size)
            // Newest first
            assertEquals(MessageContentType.IMAGE, messages[0].contentType)
            assertEquals(MessageDirection.OUTGOING, messages[1].direction)
            assertEquals(MessageDirection.INCOMING, messages[2].direction)
        }

        @Test
        fun `getMessages respects limit and offset`() {
            val now = Clock.System.now()
            repeat(5) { i ->
                store.recordMessage(MessageRecord(
                    chatId = chat.id, senderId = "user", contentType = MessageContentType.TEXT,
                    direction = MessageDirection.INCOMING, timestamp = now + (i * 1000).milliseconds,
                ))
            }

            val page = store.getMessages(chat.id, limit = 2, offset = 1)

            assertEquals(2, page.size)
        }

        @Test
        fun `getMessages returns empty for chat with no messages`() {
            val messages = store.getMessages(chat.id)
            assertTrue(messages.isEmpty())
        }

        @Test
        fun `countMessages returns correct count`() {
            assertEquals(0, store.countMessages(chat.id))

            repeat(3) {
                store.recordMessage(MessageRecord(
                    chatId = chat.id, senderId = "user", contentType = MessageContentType.TEXT,
                    direction = MessageDirection.INCOMING, timestamp = Clock.System.now(),
                ))
            }

            assertEquals(3, store.countMessages(chat.id))
        }

        @Test
        fun `messages are scoped to their chat`() {
            val chat2 = store.getOrCreateChat("signal:+9876543210", "signal")

            store.recordMessage(MessageRecord(
                chatId = chat.id, senderId = "user", contentType = MessageContentType.TEXT,
                direction = MessageDirection.INCOMING, timestamp = Clock.System.now(),
            ))
            store.recordMessage(MessageRecord(
                chatId = chat2.id, senderId = "user2", contentType = MessageContentType.TEXT,
                direction = MessageDirection.INCOMING, timestamp = Clock.System.now(),
            ))

            assertEquals(1, store.countMessages(chat.id))
            assertEquals(1, store.countMessages(chat2.id))
        }
    }

    @Nested
    inner class ChatStatsTests {

        private lateinit var chat: ChatRecord

        @BeforeEach
        fun createChat() {
            chat = store.getOrCreateChat("signal:+1234567890", "signal")
        }

        @Test
        fun `getChatStats returns zeros for empty chat`() {
            val stats = store.getChatStats(chat.id)

            assertEquals(0, stats.totalMessages)
            assertEquals(0, stats.incomingMessages)
            assertEquals(0, stats.outgoingMessages)
            assertNull(stats.firstMessageAt)
            assertNull(stats.lastMessageAt)
            assertNull(stats.avgProcessingDuration)
        }

        @Test
        fun `getChatStats counts incoming and outgoing messages`() {
            val now = Clock.System.now()
            repeat(3) { i ->
                store.recordMessage(MessageRecord(
                    chatId = chat.id, senderId = "user", contentType = MessageContentType.TEXT,
                    direction = MessageDirection.INCOMING, timestamp = now + (i * 1000).milliseconds,
                ))
            }
            repeat(2) { i ->
                store.recordMessage(MessageRecord(
                    chatId = chat.id, senderId = "fraggle", contentType = MessageContentType.TEXT,
                    direction = MessageDirection.OUTGOING, timestamp = now + ((i + 3) * 1000).milliseconds,
                    processingDuration = (1000 + i * 500).milliseconds,
                ))
            }

            val stats = store.getChatStats(chat.id)

            assertEquals(5, stats.totalMessages)
            assertEquals(3, stats.incomingMessages)
            assertEquals(2, stats.outgoingMessages)
        }

        @Test
        fun `getChatStats reports timestamp range`() {
            val now = Clock.System.now()
            store.recordMessage(MessageRecord(
                chatId = chat.id, senderId = "user", contentType = MessageContentType.TEXT,
                direction = MessageDirection.INCOMING, timestamp = now,
            ))
            store.recordMessage(MessageRecord(
                chatId = chat.id, senderId = "fraggle", contentType = MessageContentType.TEXT,
                direction = MessageDirection.OUTGOING, timestamp = now + 5.seconds,
            ))

            val stats = store.getChatStats(chat.id)

            assertNotNull(stats.firstMessageAt)
            assertNotNull(stats.lastMessageAt)
            assertTrue(stats.lastMessageAt > stats.firstMessageAt)
        }

        @Test
        fun `getChatStats computes average processing duration`() {
            val now = Clock.System.now()
            store.recordMessage(MessageRecord(
                chatId = chat.id, senderId = "fraggle", contentType = MessageContentType.TEXT,
                direction = MessageDirection.OUTGOING, timestamp = now,
                processingDuration = 1000.milliseconds,
            ))
            store.recordMessage(MessageRecord(
                chatId = chat.id, senderId = "fraggle", contentType = MessageContentType.TEXT,
                direction = MessageDirection.OUTGOING, timestamp = now + 1.seconds,
                processingDuration = 2000.milliseconds,
            ))

            val stats = store.getChatStats(chat.id)

            assertNotNull(stats.avgProcessingDuration)
            assertEquals(1500.milliseconds, stats.avgProcessingDuration)
        }
    }

    @Nested
    inner class TimeColumnTests {

        private lateinit var chat: ChatRecord

        @BeforeEach
        fun createChat() {
            chat = store.getOrCreateChat("signal:+1234567890", "signal")
        }

        @Test
        fun `Instant roundtrips through database correctly`() {
            val now = Clock.System.now()
            store.recordMessage(MessageRecord(
                chatId = chat.id, senderId = "user", contentType = MessageContentType.TEXT,
                direction = MessageDirection.INCOMING, timestamp = now,
            ))

            val messages = store.getMessages(chat.id)
            // Instant is stored as epoch milliseconds, so we compare at millisecond precision
            assertEquals(
                now.toEpochMilliseconds(),
                messages[0].timestamp.toEpochMilliseconds(),
            )
        }

        @Test
        fun `Duration roundtrips through database correctly`() {
            val duration = 2345.milliseconds
            store.recordMessage(MessageRecord(
                chatId = chat.id, senderId = "fraggle", contentType = MessageContentType.TEXT,
                direction = MessageDirection.OUTGOING, timestamp = Clock.System.now(),
                processingDuration = duration,
            ))

            val messages = store.getMessages(chat.id)
            assertEquals(duration, messages[0].processingDuration)
        }

        @Test
        fun `Duration handles sub-second values`() {
            val duration = 250.milliseconds
            store.recordMessage(MessageRecord(
                chatId = chat.id, senderId = "fraggle", contentType = MessageContentType.TEXT,
                direction = MessageDirection.OUTGOING, timestamp = Clock.System.now(),
                processingDuration = duration,
            ))

            val messages = store.getMessages(chat.id)
            assertEquals(250.milliseconds, messages[0].processingDuration)
        }

        @Test
        fun `Duration handles large values`() {
            val duration = 300.seconds
            store.recordMessage(MessageRecord(
                chatId = chat.id, senderId = "fraggle", contentType = MessageContentType.TEXT,
                direction = MessageDirection.OUTGOING, timestamp = Clock.System.now(),
                processingDuration = duration,
            ))

            val messages = store.getMessages(chat.id)
            assertEquals(300_000.milliseconds, messages[0].processingDuration)
        }
    }

    @Nested
    inner class FraggleDatabaseTests {

        @Test
        fun `FraggleDatabase creates schema on connect`() {
            val dbPath = tempDir.resolve("fresh.db")
            val fraggleDb = FraggleDatabase(dbPath)
            fraggleDb.connect()

            // Should be able to use the store immediately
            val freshStore = ExposedChatHistoryStore(fraggleDb.database)
            val chat = freshStore.getOrCreateChat("test:chat", "test")
            assertTrue(chat.id > 0)

            fraggleDb.close()
        }

        @Test
        fun `FraggleDatabase creates parent directories`() {
            val dbPath = tempDir.resolve("nested/dir/test.db")
            val fraggleDb = FraggleDatabase(dbPath)
            fraggleDb.connect()

            assertTrue(dbPath.parent.toFile().exists())
            fraggleDb.close()
        }
    }
}
