package com.example.meshlink

import com.example.meshlink.data.local.account.AccountEntity
import com.example.meshlink.data.local.message.MessageDAO
import com.example.meshlink.data.local.message.MessageEntity
import com.example.meshlink.data.local.account.AccountDAO
import com.example.meshlink.data.local.profile.ProfileDAO
import com.example.meshlink.data.local.profile.ProfileEntity
import com.example.meshlink.data.repository.ChatLocalRepository
import com.example.meshlink.data.repository.ContactLocalRepository
import com.example.meshlink.domain.model.NetworkCallEnd
import com.example.meshlink.domain.model.NetworkCallRequest
import com.example.meshlink.domain.model.NetworkCallResponse
import com.example.meshlink.domain.model.NetworkDevice
import com.example.meshlink.domain.model.NetworkKeepalive
import com.example.meshlink.domain.model.NetworkMessageAck
import com.example.meshlink.domain.model.NetworkProfileRequest
import com.example.meshlink.domain.model.NetworkProfileResponse
import com.example.meshlink.domain.model.NetworkTextMessage
import com.example.meshlink.domain.model.device.Account
import com.example.meshlink.domain.model.device.Contact
import com.example.meshlink.domain.model.device.Profile
import com.example.meshlink.domain.model.device.toAccountEntity
import com.example.meshlink.domain.model.device.toProfileEntity
import com.example.meshlink.domain.model.message.AudioMessage
import com.example.meshlink.domain.model.message.FileMessage
import com.example.meshlink.domain.model.message.MessageState
import com.example.meshlink.domain.model.message.MessageType
import com.example.meshlink.domain.model.message.TextMessage
import com.example.meshlink.domain.repository.ChatRepository
import com.example.meshlink.domain.repository.ContactRepository
import com.example.meshlink.domain.repository.OwnAccountRepository
import com.example.meshlink.domain.repository.OwnProfileRepository
import com.example.meshlink.network.NetworkManager
import com.example.meshlink.network.service.ClientService
import com.example.meshlink.network.service.ServerService
import com.example.meshlink.network.wifidirect.WiFiDirectBroadcastReceiver
import com.example.meshlink.data.local.FileManager
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.Suite

@RunWith(Suite::class)
@Suite.SuiteClasses(
    MeshLinkSuite.ModelTests::class,
    MeshLinkSuite.ChatRepositoryTests::class,
    MeshLinkSuite.ContactRepositoryTests::class,
    MeshLinkSuite.NetworkManagerTests::class,
    MeshLinkSuite.ClientServerTests::class,
    MeshLinkSuite.SerializationTests::class,
)
class MeshLinkSuite {

    // ════════════════════════════════════════════════════════════════════════
    // 1. МОДЕЛИ ДАННЫХ
    // ════════════════════════════════════════════════════════════════════════

    class ModelTests {

        @Test
        fun `TextMessage toMessageEntity — all fields mapped correctly`() {
            val msg = TextMessage(1L, "sender", "receiver", 1000L, MessageState.MESSAGE_SENT, "hello")
            val entity = msg.toMessageEntity()
            assertEquals(1L,                        entity.messageId)
            assertEquals("sender",                  entity.senderId)
            assertEquals("receiver",                entity.receiverId)
            assertEquals(1000L,                     entity.timestamp)
            assertEquals(MessageState.MESSAGE_SENT,  entity.messageState)
            assertEquals(MessageType.TEXT_MESSAGE,   entity.messageType)
            assertEquals("hello",                   entity.content)
        }

        @Test
        fun `FileMessage toMessageEntity — type is FILE_MESSAGE`() {
            val msg = FileMessage(2L, "s", "r", 0L, MessageState.MESSAGE_RECEIVED, "file.jpg")
            val entity = msg.toMessageEntity()
            assertEquals(MessageType.FILE_MESSAGE, entity.messageType)
            assertEquals("file.jpg", entity.content)
        }

        @Test
        fun `AudioMessage toMessageEntity — type is AUDIO_MESSAGE`() {
            val msg = AudioMessage(3L, "s", "r", 0L, MessageState.MESSAGE_READ, "audio.3gp")
            val entity = msg.toMessageEntity()
            assertEquals(MessageType.AUDIO_MESSAGE, entity.messageType)
            assertEquals("audio.3gp", entity.content)
        }

        @Test
        fun `Message compareTo — orders by timestamp ascending`() {
            val older = TextMessage(1L, "s", "r", 100L, MessageState.MESSAGE_SENT, "old")
            val newer = TextMessage(2L, "s", "r", 900L, MessageState.MESSAGE_SENT, "new")
            assertTrue(older < newer)
            assertTrue(newer > older)
        }

        @Test
        fun `Message compareTo — same timestamp equals zero`() {
            val a = TextMessage(1L, "s", "r", 500L, MessageState.MESSAGE_SENT, "a")
            val b = TextMessage(2L, "s", "r", 500L, MessageState.MESSAGE_SENT, "b")
            assertEquals(0, a.compareTo(b))
        }

        @Test
        fun `MessageState — all three values exist`() {
            val states = MessageState.values()
            assertTrue(states.contains(MessageState.MESSAGE_SENT))
            assertTrue(states.contains(MessageState.MESSAGE_RECEIVED))
            assertTrue(states.contains(MessageState.MESSAGE_READ))
        }

        @Test
        fun `Account toAccountEntity — roundtrip`() {
            val account = Account("peer123", 9999L)
            val entity = account.toAccountEntity()
            assertEquals(account.peerId, entity.peerId)
            assertEquals(account.profileUpdateTimestamp, entity.profileUpdateTimestamp)
        }

        @Test
        fun `Profile toProfileEntity — roundtrip`() {
            val profile = Profile("peer123", 1234L, "Alice", "avatar.jpg")
            val entity = profile.toProfileEntity()
            assertEquals(profile.peerId, entity.peerId)
            assertEquals(profile.username, entity.username)
            assertEquals(profile.imageFileName, entity.imageFileName)
            assertEquals(profile.updateTimestamp, entity.updateTimestamp)
        }

        @Test
        fun `Contact peerId delegates to account`() {
            assertEquals("abc", Contact(Account("abc", 0L), null).peerId)
        }

        @Test
        fun `Contact username — null when no profile`() {
            assertNull(Contact(Account("x", 0L), null).username)
        }

        @Test
        fun `Contact username — from profile when present`() {
            assertEquals("Bob", Contact(Account("x", 0L), Profile("x", 0L, "Bob", null)).username)
        }

        @Test
        fun `Contact compareTo — alphabetical by username`() {
            val alice = Contact(Account("a", 0L), Profile("a", 0L, "Alice", null))
            val bob   = Contact(Account("b", 0L), Profile("b", 0L, "Bob",   null))
            assertTrue(alice < bob)
        }

        @Test
        fun `NetworkDevice — fields accessible`() {
            val d = NetworkDevice("pid", "User", "ABCD", "pubkey", "192.168.1.1", 12345L)
            assertEquals("pid", d.peerId)
            assertEquals("User", d.username)
            assertEquals("ABCD", d.shortCode)
            assertEquals("192.168.1.1", d.ipAddress)
            assertEquals(12345L, d.keepalive)
        }

        @Test
        fun `NetworkDevice — ipAddress can be null`() {
            assertNull(NetworkDevice("p", "U", "XX", "k", null, 0L).ipAddress)
        }

        @Test
        fun `NetworkKeepalive — holds list of devices`() {
            val kp = NetworkKeepalive(listOf(
                NetworkDevice("p1", "A", "AA", "k", "1.1.1.1", 0L),
                NetworkDevice("p2", "B", "BB", "k", "2.2.2.2", 0L),
            ))
            assertEquals(2, kp.devices.size)
        }

        @Test
        fun `NetworkTextMessage — fields correct`() {
            val msg = NetworkTextMessage(10L, "s", "r", 500L, "hi")
            assertEquals(10L, msg.messageId); assertEquals("s", msg.senderId)
            assertEquals("r", msg.receiverId); assertEquals(500L, msg.timestamp)
            assertEquals("hi", msg.text)
        }

        @Test
        fun `NetworkProfileRequest — senderId receiverId`() {
            val req = NetworkProfileRequest("from", "to")
            assertEquals("from", req.senderId); assertEquals("to", req.receiverId)
        }

        @Test
        fun `NetworkProfileResponse — null imageBase64 allowed`() {
            assertNull(NetworkProfileResponse("s", "r", "name", null).imageBase64)
        }

        @Test
        fun `NetworkCallResponse — accepted flag`() {
            assertTrue(NetworkCallResponse("s", "r", true).accepted)
            assertFalse(NetworkCallResponse("s", "r", false).accepted)
        }

        @Test
        fun `NetworkMessageAck — messageId`() {
            assertEquals(42L, NetworkMessageAck(42L, "s", "r").messageId)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. CHAT REPOSITORY
    // ════════════════════════════════════════════════════════════════════════

    @OptIn(ExperimentalCoroutinesApi::class)
    class ChatRepositoryTests {

        private lateinit var accountDAO: AccountDAO
        private lateinit var profileDAO: ProfileDAO
        private lateinit var messageDAO: MessageDAO
        private lateinit var repo: ChatLocalRepository

        @Before
        fun setUp() {
            accountDAO = mockk(); profileDAO = mockk(); messageDAO = mockk()
            repo = ChatLocalRepository(accountDAO, profileDAO, messageDAO)
        }

        @Test
        fun `getAllChatPreviews — one account with profile — returns preview`() = runTest {
            every { accountDAO.getAllAsFlow() } returns flowOf(listOf(AccountEntity("p1", 0L)))
            every { profileDAO.getAllAsFlow() }  returns flowOf(listOf(ProfileEntity("p1", 0L, "Alice", null)))
            coEvery { messageDAO.countUnread(any()) } returns 0L
            coEvery { messageDAO.getLastByPeerId(any()) } returns null
            val previews = repo.getAllChatPreviewsAsFlow().first()
            assertEquals(1, previews.size)
            assertEquals("Alice", previews[0].contact.profile?.username)
        }

        @Test
        fun `getAllChatPreviews — no accounts — empty list`() = runTest {
            every { accountDAO.getAllAsFlow() } returns flowOf(emptyList())
            every { profileDAO.getAllAsFlow() }  returns flowOf(emptyList())
            assertTrue(repo.getAllChatPreviewsAsFlow().first().isEmpty())
        }

        @Test
        fun `getAllChatPreviews — unread count reflected`() = runTest {
            every { accountDAO.getAllAsFlow() } returns flowOf(listOf(AccountEntity("p1", 0L)))
            every { profileDAO.getAllAsFlow() }  returns flowOf(emptyList())
            coEvery { messageDAO.countUnread("p1") } returns 7L
            coEvery { messageDAO.getLastByPeerId(any()) } returns null
            assertEquals(7, repo.getAllChatPreviewsAsFlow().first()[0].unreadCount)
        }

        @Test
        fun `getAllChatPreviews — sorted newest first`() = runTest {
            every { accountDAO.getAllAsFlow() } returns flowOf(
                listOf(AccountEntity("p1", 0L), AccountEntity("p2", 0L))
            )
            every { profileDAO.getAllAsFlow() } returns flowOf(emptyList())
            coEvery { messageDAO.countUnread(any()) } returns 0L
            coEvery { messageDAO.getLastByPeerId("p1") } returns makeEntity("p1", ts = 100L)
            coEvery { messageDAO.getLastByPeerId("p2") } returns makeEntity("p2", ts = 999L)
            val previews = repo.getAllChatPreviewsAsFlow().first()
            assertEquals("p2", previews[0].contact.account.peerId)
            assertEquals("p1", previews[1].contact.account.peerId)
        }

        @Test
        fun `getAllChatPreviews — account without profile has null profile`() = runTest {
            every { accountDAO.getAllAsFlow() } returns flowOf(listOf(AccountEntity("p1", 0L)))
            every { profileDAO.getAllAsFlow() }  returns flowOf(emptyList())
            coEvery { messageDAO.countUnread(any()) } returns 0L
            coEvery { messageDAO.getLastByPeerId(any()) } returns null
            assertNull(repo.getAllChatPreviewsAsFlow().first().first().contact.profile)
        }

        @Test
        fun `addMessage — returns id from DAO`() = runTest {
            coEvery { messageDAO.insert(any()) } returns 55L
            assertEquals(55L, repo.addMessage(TextMessage(0L, "s", "r", 0L, MessageState.MESSAGE_SENT, "hi")))
        }

        @Test
        fun `addMessage — DAO insert called with correct type`() = runTest {
            val slot = slot<MessageEntity>()
            coEvery { messageDAO.insert(capture(slot)) } returns 1L
            repo.addMessage(TextMessage(0L, "s", "r", 0L, MessageState.MESSAGE_SENT, "text"))
            assertEquals(MessageType.TEXT_MESSAGE, slot.captured.messageType)
            assertEquals("text", slot.captured.content)
        }

        @Test
        fun `updateMessageState — calls DAO with correct args`() = runTest {
            coEvery { messageDAO.updateState(any(), any()) } just Runs
            repo.updateMessageState(10L, MessageState.MESSAGE_READ)
            coVerify { messageDAO.updateState(10L, MessageState.MESSAGE_READ) }
        }

        @Test
        fun `getMessagesByPeerIdAsFlow — maps entity to TextMessage`() = runTest {
            every { messageDAO.getByPeerIdAsFlow("p1") } returns flowOf(listOf(makeEntity("p1", text = "hello")))
            val messages = repo.getMessagesByPeerIdAsFlow("p1").first()
            assertEquals(1, messages.size)
            assertEquals("hello", (messages[0] as TextMessage).text)
        }

        @Test
        fun `getMessagesByPeerIdAsFlow — empty flow`() = runTest {
            every { messageDAO.getByPeerIdAsFlow("p1") } returns flowOf(emptyList())
            assertTrue(repo.getMessagesByPeerIdAsFlow("p1").first().isEmpty())
        }

        @Test
        fun `getMessageByMessageId — found — returns message`() = runTest {
            coEvery { messageDAO.getById(5L) } returns makeEntity("p1", messageId = 5L)
            assertNotNull(repo.getMessageByMessageId(5L))
        }

        @Test
        fun `getMessageByMessageId — not found — returns null`() = runTest {
            coEvery { messageDAO.getById(any()) } returns null
            assertNull(repo.getMessageByMessageId(999L))
        }

        @Test
        fun `getAllMessagesByReceiverPeerId — returns list`() = runTest {
            coEvery { messageDAO.getAllByReceiverId("r1") } returns listOf(makeEntity("s1"))
            assertEquals(1, repo.getAllMessagesByReceiverPeerId("r1").size)
        }

        private fun makeEntity(
            peerId: String, messageId: Long = 0L, ts: Long = 0L, text: String = "msg"
        ) = MessageEntity(messageId, peerId, "other", ts, MessageState.MESSAGE_SENT, MessageType.TEXT_MESSAGE, text)
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3. CONTACT REPOSITORY
    // ════════════════════════════════════════════════════════════════════════

    @OptIn(ExperimentalCoroutinesApi::class)
    class ContactRepositoryTests {

        private lateinit var accountDAO: AccountDAO
        private lateinit var profileDAO: ProfileDAO
        private lateinit var repo: ContactLocalRepository

        @Before
        fun setUp() {
            accountDAO = mockk(); profileDAO = mockk()
            repo = ContactLocalRepository(accountDAO, profileDAO)
        }

        @Test
        fun `getAllContactsAsFlow — joins account and profile`() = runTest {
            every { accountDAO.getAllAsFlow() } returns flowOf(listOf(AccountEntity("p1", 0L)))
            every { profileDAO.getAllAsFlow() }  returns flowOf(listOf(ProfileEntity("p1", 0L, "Alice", null)))
            val contacts = repo.getAllContactsAsFlow().first()
            assertEquals(1, contacts.size); assertEquals("Alice", contacts[0].username)
        }

        @Test
        fun `getAllContactsAsFlow — no accounts — empty`() = runTest {
            every { accountDAO.getAllAsFlow() } returns flowOf(emptyList())
            every { profileDAO.getAllAsFlow() }  returns flowOf(emptyList())
            assertTrue(repo.getAllContactsAsFlow().first().isEmpty())
        }

        @Test
        fun `getContactByPeerIdAsFlow — account exists — returns contact`() = runTest {
            every { accountDAO.getByPeerIdAsFlow("p1") } returns flowOf(AccountEntity("p1", 0L))
            every { profileDAO.getByPeerIdAsFlow("p1") } returns flowOf(ProfileEntity("p1", 0L, "Bob", null))
            val contact = repo.getContactByPeerIdAsFlow("p1").first()
            assertNotNull(contact); assertEquals("Bob", contact!!.username)
        }

        @Test
        fun `getContactByPeerIdAsFlow — account missing — returns null`() = runTest {
            every { accountDAO.getByPeerIdAsFlow("p1") } returns flowOf(null)
            every { profileDAO.getByPeerIdAsFlow("p1") } returns flowOf(null)
            assertNull(repo.getContactByPeerIdAsFlow("p1").first())
        }

        @Test
        fun `getContactByPeerIdAsFlow — account exists but no profile`() = runTest {
            every { accountDAO.getByPeerIdAsFlow("p1") } returns flowOf(AccountEntity("p1", 0L))
            every { profileDAO.getByPeerIdAsFlow("p1") } returns flowOf(null)
            val contact = repo.getContactByPeerIdAsFlow("p1").first()
            assertNotNull(contact); assertNull(contact!!.profile)
        }

        @Test
        fun `addOrUpdateAccount — calls DAO insertOrUpdate`() = runTest {
            coEvery { accountDAO.insertOrUpdate(any()) } just Runs
            repo.addOrUpdateAccount(Account("p1", 0L))
            coVerify { accountDAO.insertOrUpdate(AccountEntity("p1", 0L)) }
        }

        @Test
        fun `addOrUpdateProfile — calls DAO insertOrUpdate`() = runTest {
            coEvery { profileDAO.insertOrUpdate(any()) } just Runs
            repo.addOrUpdateProfile(Profile("p1", 0L, "Test", null))
            coVerify { profileDAO.insertOrUpdate(any()) }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4. NETWORK MANAGER
    // ════════════════════════════════════════════════════════════════════════

    @OptIn(ExperimentalCoroutinesApi::class)
    class NetworkManagerTests {

        private lateinit var ownAccountRepo: OwnAccountRepository
        private lateinit var ownProfileRepo: OwnProfileRepository
        private lateinit var chatRepo: ChatRepository
        private lateinit var contactRepo: ContactRepository
        private lateinit var fileManager: FileManager
        private lateinit var clientService: ClientService
        private lateinit var serverService: ServerService
        private lateinit var wifiReceiver: WiFiDirectBroadcastReceiver
        private lateinit var networkManager: NetworkManager

        private val groupOwnerFlow   = MutableStateFlow<String?>(null)
        private val isGroupOwnerFlow = MutableStateFlow(false)
        private val peersFlow        = MutableStateFlow(emptyList<android.net.wifi.p2p.WifiP2pDevice>())

        private val ownPeerId    = "a".repeat(64)
        private val remotePeerId = "b".repeat(64)
        private val remoteIp     = "192.168.49.2"

        @Before
        fun setUp() {
            Dispatchers.setMain(StandardTestDispatcher())

            ownAccountRepo = mockk(relaxed = true)
            ownProfileRepo = mockk(relaxed = true) {
                coEvery { getProfile() } returns Profile(ownPeerId, 0L, "Me", null)
            }
            chatRepo      = mockk(relaxed = true)
            contactRepo   = mockk(relaxed = true)
            fileManager   = mockk(relaxed = true)
            clientService = mockk(relaxed = true)
            serverService = mockk(relaxed = true) {
                coEvery { listenKeepalive() }          coAnswers { delay(Long.MAX_VALUE); null }
                coEvery { listenTextMessage() }        coAnswers { delay(Long.MAX_VALUE); null }
                coEvery { listenProfileRequest() }     coAnswers { delay(Long.MAX_VALUE); null }
                coEvery { listenProfileResponse() }    coAnswers { delay(Long.MAX_VALUE); null }
                coEvery { listenFileMessage() }        coAnswers { delay(Long.MAX_VALUE); null }
                coEvery { listenAudioMessage() }       coAnswers { delay(Long.MAX_VALUE); null }
                coEvery { listenMessageReceivedAck() } coAnswers { delay(Long.MAX_VALUE); null }
                coEvery { listenMessageReadAck() }     coAnswers { delay(Long.MAX_VALUE); null }
                coEvery { listenCallRequest() }        coAnswers { delay(Long.MAX_VALUE); null }
                coEvery { listenCallResponse() }       coAnswers { delay(Long.MAX_VALUE); null }
                coEvery { listenCallEnd() }            coAnswers { delay(Long.MAX_VALUE); null }
                coEvery { listenCallFragment() }       coAnswers { delay(Long.MAX_VALUE); null }
            }
            wifiReceiver = mockk(relaxed = true) {
                every { groupOwnerAddress } returns groupOwnerFlow
                every { isGroupOwner }      returns isGroupOwnerFlow
                every { peers }             returns peersFlow
            }

            networkManager = NetworkManager(
                ownAccountRepo, ownProfileRepo, mockk(relaxed = true),
                wifiReceiver, chatRepo, contactRepo, fileManager
            )
            inject("clientService", clientService)
            inject("serverService", serverService)
            setField("ownPeerId", ownPeerId)
        }

        @After
        fun tearDown() {
            Dispatchers.resetMain()
            unmockkAll()
        }

        @Test
        fun `initially connectedDevices is empty`() = runTest {
            assertTrue(networkManager.connectedDevices.first().isEmpty())
        }

        @Test
        fun `handleKeepalive — adds remote device`() = runTest {
            call("handleKeepalive", NetworkKeepalive(listOf(device(remotePeerId, "Alice", remoteIp))))
            advanceUntilIdle()
            val result = networkManager.connectedDevices.first()
            assertTrue(result.containsKey(remotePeerId))
            assertEquals("Alice", result[remotePeerId]?.username)
        }

        @Test
        fun `handleKeepalive — ignores own peerId`() = runTest {
            call("handleKeepalive", NetworkKeepalive(listOf(device(ownPeerId, "Me", "192.168.49.1"))))
            advanceUntilIdle()
            assertTrue(networkManager.connectedDevices.first().isEmpty())
        }

        @Test
        fun `handleKeepalive — ignores blank peerId`() = runTest {
            call("handleKeepalive", NetworkKeepalive(listOf(device("", "Ghost", remoteIp))))
            advanceUntilIdle()
            assertTrue(networkManager.connectedDevices.first().isEmpty())
        }

        @Test
        fun `handleKeepalive — saves account to contactRepo`() = runTest {
            call("handleKeepalive", NetworkKeepalive(listOf(device(remotePeerId, "Bob", remoteIp))))
            advanceUntilIdle()
            coVerify { contactRepo.addOrUpdateAccount(Account(remotePeerId, any())) }
        }

        @Test
        fun `handleKeepalive — two devices — both added`() = runTest {
            call("handleKeepalive", NetworkKeepalive(listOf(
                device("p1", "A", "192.168.49.2"),
                device("p2", "B", "192.168.49.3"),
            )))
            advanceUntilIdle()
            assertEquals(2, networkManager.connectedDevices.first().size)
        }

        @Test
        fun `pruneDeadDevices — removes stale device`() = runTest {
            setDevices(mapOf(remotePeerId to device(remotePeerId, "Old", remoteIp)
                .copy(keepalive = System.currentTimeMillis() - 10_000)))
            call("pruneDeadDevices")
            assertTrue(networkManager.connectedDevices.first().isEmpty())
        }

        @Test
        fun `pruneDeadDevices — keeps fresh device`() = runTest {
            setDevices(mapOf(remotePeerId to device(remotePeerId, "Fresh", remoteIp)))
            call("pruneDeadDevices")
            assertEquals(1, networkManager.connectedDevices.first().size)
        }

        @Test
        fun `pruneDeadDevices — boundary 6001ms — removed`() = runTest {
            setDevices(mapOf(remotePeerId to device(remotePeerId, "X", remoteIp)
                .copy(keepalive = System.currentTimeMillis() - 6001)))
            call("pruneDeadDevices")
            assertTrue(networkManager.connectedDevices.first().isEmpty())
        }

        @Test
        fun `sendTextMessage — peer connected — saves and sends`() = runTest {
            setDevices(mapOf(remotePeerId to device(remotePeerId, "R", remoteIp)))
            coEvery { chatRepo.addMessage(any()) } returns 1L
            networkManager.sendTextMessage(remotePeerId, "Hello")
            advanceUntilIdle()
            coVerify { chatRepo.addMessage(any()) }
            coVerify { clientService.sendTextMessage(remoteIp, any()) }
        }

        @Test
        fun `sendTextMessage — peer not connected — nothing sent`() = runTest {
            networkManager.sendTextMessage(remotePeerId, "Hello")
            advanceUntilIdle()
            coVerify(exactly = 0) { chatRepo.addMessage(any()) }
            coVerify(exactly = 0) { clientService.sendTextMessage(any(), any()) }
        }

        @Test
        fun `sendTextMessage — message content preserved`() = runTest {
            setDevices(mapOf(remotePeerId to device(remotePeerId, "R", remoteIp)))
            val slot = slot<NetworkTextMessage>()
            coEvery { chatRepo.addMessage(any()) } returns 1L
            coEvery { clientService.sendTextMessage(any(), capture(slot)) } just Runs
            networkManager.sendTextMessage(remotePeerId, "Test content 123")
            advanceUntilIdle()
            assertEquals("Test content 123", slot.captured.text)
            assertEquals(ownPeerId,           slot.captured.senderId)
            assertEquals(remotePeerId,        slot.captured.receiverId)
        }

        @Test
        fun `sendMessageReadAck — sends to correct ip`() = runTest {
            setDevices(mapOf(remotePeerId to device(remotePeerId, "R", remoteIp)))
            networkManager.sendMessageReadAck(remotePeerId, 77L)
            advanceUntilIdle()
            coVerify { clientService.sendMessageReadAck(remoteIp, NetworkMessageAck(77L, ownPeerId, remotePeerId)) }
        }

        @Test
        fun `sendMessageReadAck — peer absent — nothing sent`() = runTest {
            networkManager.sendMessageReadAck("unknown", 1L)
            advanceUntilIdle()
            coVerify(exactly = 0) { clientService.sendMessageReadAck(any(), any()) }
        }

        @Test
        fun `sendProfileRequest — peer connected — calls client`() = runTest {
            setDevices(mapOf(remotePeerId to device(remotePeerId, "R", remoteIp)))
            networkManager.sendProfileRequest(remotePeerId)
            advanceUntilIdle()
            coVerify { clientService.sendProfileRequest(remoteIp, any()) }
        }

        @Test
        fun `sendProfileRequest — peer absent — nothing sent`() = runTest {
            networkManager.sendProfileRequest("ghost")
            advanceUntilIdle()
            coVerify(exactly = 0) { clientService.sendProfileRequest(any(), any()) }
        }

        @Test
        fun `sendCallRequest — peer connected — sends`() = runTest {
            setDevices(mapOf(remotePeerId to device(remotePeerId, "R", remoteIp)))
            networkManager.sendCallRequest(remotePeerId)
            advanceUntilIdle()
            coVerify { clientService.sendCallRequest(remoteIp, any()) }
        }

        @Test
        fun `sendCallRequest — peer absent — nothing sent`() = runTest {
            networkManager.sendCallRequest("ghost")
            advanceUntilIdle()
            coVerify(exactly = 0) { clientService.sendCallRequest(any(), any()) }
        }

        @Test
        fun `sendCallResponse accepted — sends with accepted=true`() = runTest {
            setDevices(mapOf(remotePeerId to device(remotePeerId, "R", remoteIp)))
            val slot = slot<NetworkCallResponse>()
            coEvery { clientService.sendCallResponse(any(), capture(slot)) } just Runs
            networkManager.sendCallResponse(remotePeerId, true)
            advanceUntilIdle()
            assertTrue(slot.captured.accepted)
        }

        @Test
        fun `sendCallResponse rejected — sends with accepted=false`() = runTest {
            setDevices(mapOf(remotePeerId to device(remotePeerId, "R", remoteIp)))
            val slot = slot<NetworkCallResponse>()
            coEvery { clientService.sendCallResponse(any(), capture(slot)) } just Runs
            networkManager.sendCallResponse(remotePeerId, false)
            advanceUntilIdle()
            assertFalse(slot.captured.accepted)
        }

        @Test
        fun `sendCallEnd — peer connected — sends`() = runTest {
            setDevices(mapOf(remotePeerId to device(remotePeerId, "R", remoteIp)))
            networkManager.sendCallEnd(remotePeerId)
            advanceUntilIdle()
            coVerify { clientService.sendCallEnd(remoteIp, any()) }
        }

        @Test
        fun `resetCallState — clears callRequest`() = runTest {
            setField("_callRequest", MutableStateFlow<NetworkCallRequest?>(NetworkCallRequest("a", "b")))
            networkManager.resetCallState()
            assertNull(networkManager.callRequest.first())
        }

        @Test
        fun `resetCallState — clears callResponse`() = runTest {
            setField("_callResponse", MutableStateFlow<NetworkCallResponse?>(NetworkCallResponse("a", "b", true)))
            networkManager.resetCallState()
            assertNull(networkManager.callResponse.first())
        }

        @Test
        fun `resetCallState — clears callEnd`() = runTest {
            setField("_callEnd", MutableStateFlow<NetworkCallEnd?>(NetworkCallEnd("a", "b")))
            networkManager.resetCallState()
            assertNull(networkManager.callEnd.first())
        }

        // ── helpers ───────────────────────────────────────────────────────────

        private fun device(peerId: String, name: String, ip: String) =
            NetworkDevice(peerId, name, peerId.take(4), "pubhex", ip, System.currentTimeMillis())

        private fun setDevices(map: Map<String, NetworkDevice>) {
            val f = NetworkManager::class.java.getDeclaredField("_connectedDevices")
            f.isAccessible = true
            (f.get(networkManager) as MutableStateFlow<Map<String, NetworkDevice>>).value = map
        }

        private fun setField(name: String, value: Any) {
            val f = NetworkManager::class.java.getDeclaredField(name)
            f.isAccessible = true
            f.set(networkManager, value)
        }

        private fun inject(name: String, value: Any) = setField(name, value)

        private fun call(method: String, vararg args: Any?) {
            val types = args.map { it!!::class.java }.toTypedArray()
            val m = NetworkManager::class.java.getDeclaredMethod(method, *types)
            m.isAccessible = true
            m.invoke(networkManager, *args)
        }

        private fun call(method: String) {
            val m = NetworkManager::class.java.getDeclaredMethod(method)
            m.isAccessible = true
            m.invoke(networkManager)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 5. CLIENT ↔ SERVER (реальный TCP на localhost)
    // ════════════════════════════════════════════════════════════════════════

    @OptIn(ExperimentalCoroutinesApi::class)
    class ClientServerTests {

        private val client = ClientService()

        @Test
        fun `keepalive roundtrip — single device`() = runTest {
            val device = NetworkDevice("p1", "Alice", "ABCD", "key", "127.0.0.1", 0L)
            withServer(ServerService.PORT_KEEPALIVE) { server ->
                launch { client.sendKeepalive("127.0.0.1", NetworkKeepalive(listOf(device))) }
                val rx = server.listenKeepalive()
                assertNotNull(rx)
                assertEquals("p1",    rx!!.devices[0].peerId)
                assertEquals("Alice", rx.devices[0].username)
            }
        }

        @Test
        fun `keepalive roundtrip — multiple devices`() = runTest {
            val devices = (1..3).map { NetworkDevice("p$it", "User$it", "XX$it", "k", "1.1.1.$it", 0L) }
            withServer(ServerService.PORT_KEEPALIVE) { server ->
                launch { client.sendKeepalive("127.0.0.1", NetworkKeepalive(devices)) }
                assertEquals(3, server.listenKeepalive()!!.devices.size)
            }
        }

        @Test
        fun `text message roundtrip — content preserved`() = runTest {
            val msg = NetworkTextMessage(1L, "sender", "receiver", 1000L, "Hello!")
            withServer(ServerService.PORT_TEXT_MESSAGE) { server ->
                launch { client.sendTextMessage("127.0.0.1", msg) }
                val rx = server.listenTextMessage()
                assertNotNull(rx)
                assertEquals(1L,       rx!!.messageId)
                assertEquals("sender", rx.senderId)
                assertEquals("Hello!", rx.text)
            }
        }

        @Test
        fun `text message — unicode preserved`() = runTest {
            val text = "Привет мир! 🌍"
            withServer(ServerService.PORT_TEXT_MESSAGE) { server ->
                launch { client.sendTextMessage("127.0.0.1", NetworkTextMessage(1L, "s", "r", 0L, text)) }
                assertEquals(text, server.listenTextMessage()!!.text)
            }
        }

        @Test
        fun `text message — long text`() = runTest {
            val longText = "X".repeat(50_000)
            withServer(ServerService.PORT_TEXT_MESSAGE) { server ->
                launch { client.sendTextMessage("127.0.0.1", NetworkTextMessage(1L, "s", "r", 0L, longText)) }
                assertEquals(50_000, server.listenTextMessage()!!.text.length)
            }
        }

        @Test
        fun `profile request roundtrip`() = runTest {
            withServer(ServerService.PORT_PROFILE_REQUEST) { server ->
                launch { client.sendProfileRequest("127.0.0.1", NetworkProfileRequest("from", "to")) }
                val rx = server.listenProfileRequest()
                assertEquals("from", rx!!.senderId); assertEquals("to", rx.receiverId)
            }
        }

        @Test
        fun `profile response — null imageBase64`() = runTest {
            withServer(ServerService.PORT_PROFILE_RESPONSE) { server ->
                launch { client.sendProfileResponse("127.0.0.1", NetworkProfileResponse("s", "r", "Bob", null)) }
                val rx = server.listenProfileResponse()
                assertEquals("Bob", rx!!.username); assertNull(rx.imageBase64)
            }
        }

        @Test
        fun `profile response — with imageBase64`() = runTest {
            val b64 = "SGVsbG8gV29ybGQ="
            withServer(ServerService.PORT_PROFILE_RESPONSE) { server ->
                launch { client.sendProfileResponse("127.0.0.1", NetworkProfileResponse("s", "r", "Alice", b64)) }
                assertEquals(b64, server.listenProfileResponse()!!.imageBase64)
            }
        }

        @Test
        fun `message received ack roundtrip`() = runTest {
            withServer(ServerService.PORT_MESSAGE_RECEIVED_ACK) { server ->
                launch { client.sendMessageReceivedAck("127.0.0.1", NetworkMessageAck(42L, "s", "r")) }
                assertEquals(42L, server.listenMessageReceivedAck()!!.messageId)
            }
        }

        @Test
        fun `message read ack roundtrip`() = runTest {
            withServer(ServerService.PORT_MESSAGE_READ_ACK) { server ->
                launch { client.sendMessageReadAck("127.0.0.1", NetworkMessageAck(88L, "s", "r")) }
                assertEquals(88L, server.listenMessageReadAck()!!.messageId)
            }
        }

        @Test
        fun `call request roundtrip`() = runTest {
            withServer(ServerService.PORT_CALL_REQUEST) { server ->
                launch { client.sendCallRequest("127.0.0.1", NetworkCallRequest("caller", "callee")) }
                val rx = server.listenCallRequest()
                assertEquals("caller", rx!!.senderId); assertEquals("callee", rx.receiverId)
            }
        }

        @Test
        fun `call response accepted=true roundtrip`() = runTest {
            withServer(ServerService.PORT_CALL_RESPONSE) { server ->
                launch { client.sendCallResponse("127.0.0.1", NetworkCallResponse("a", "b", true)) }
                assertTrue(server.listenCallResponse()!!.accepted)
            }
        }

        @Test
        fun `call response accepted=false roundtrip`() = runTest {
            withServer(ServerService.PORT_CALL_RESPONSE) { server ->
                launch { client.sendCallResponse("127.0.0.1", NetworkCallResponse("a", "b", false)) }
                assertFalse(server.listenCallResponse()!!.accepted)
            }
        }

        @Test
        fun `call end roundtrip`() = runTest {
            withServer(ServerService.PORT_CALL_END) { server ->
                launch { client.sendCallEnd("127.0.0.1", NetworkCallEnd("p1", "p2")) }
                assertEquals("p1", server.listenCallEnd()!!.senderId)
            }
        }

        @Test
        fun `call fragment — bytes intact`() = runTest {
            val bytes = byteArrayOf(0x01, 0x7F, 0xFF.toByte(), 0x00, 0x42)
            withServer(ServerService.PORT_CALL_FRAGMENT) { server ->
                launch { client.sendCallFragment("127.0.0.1", bytes) }
                assertArrayEquals(bytes, server.listenCallFragment())
            }
        }

        @Test
        fun `send to unreachable address — does not throw`() = runTest {
            client.sendTextMessage("192.0.2.1", NetworkTextMessage(1L, "s", "r", 0L, "test"))
        }

        // ── helper ────────────────────────────────────────────────────────────
        //
        // ИСПРАВЛЕНИЕ: block получает CoroutineScope как receiver (suspend CoroutineScope.(…))
        // coroutineScope { } создаёт scope и ждёт завершения всех launch { } внутри
        // только потом вызывается server.shutdown() в finally.
        // Именно поэтому launch { } теперь доступен внутри блока.

        private suspend fun withServer(
            port: Int,
            block: suspend CoroutineScope.(ServerService) -> Unit
        ) {
            val server = ServerService()
            try {
                coroutineScope { block(server) }
            } finally {
                server.shutdown()
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 6. СЕРИАЛИЗАЦИЯ JSON
    // ════════════════════════════════════════════════════════════════════════

    class SerializationTests {

        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        @Test
        fun `NetworkDevice serialize — deserialize roundtrip`() {
            val d = NetworkDevice("pid", "User", "ABCD", "pubkey", "1.2.3.4", 999L)
            assertEquals(d, json.decodeFromString(NetworkDevice.serializer(), json.encodeToString(NetworkDevice.serializer(), d)))
        }

        @Test
        fun `NetworkDevice — null ipAddress survives roundtrip`() {
            val d = NetworkDevice("pid", "User", "ABCD", "pubkey", null, 0L)
            assertNull(json.decodeFromString(NetworkDevice.serializer(), json.encodeToString(NetworkDevice.serializer(), d)).ipAddress)
        }

        @Test
        fun `NetworkKeepalive serialize — deserialize`() {
            val kp = NetworkKeepalive(listOf(
                NetworkDevice("p1", "A", "AA", "k", "1.1.1.1", 0L),
                NetworkDevice("p2", "B", "BB", "k", null, 0L),
            ))
            val decoded = json.decodeFromString(NetworkKeepalive.serializer(), json.encodeToString(NetworkKeepalive.serializer(), kp))
            assertEquals(2, decoded.devices.size)
            assertNull(decoded.devices[1].ipAddress)
        }

        @Test
        fun `NetworkTextMessage serialize — deserialize`() {
            val msg = NetworkTextMessage(7L, "s", "r", 1234567890L, "Hello 🌍")
            assertEquals(msg, json.decodeFromString(NetworkTextMessage.serializer(), json.encodeToString(NetworkTextMessage.serializer(), msg)))
        }

        @Test
        fun `NetworkProfileResponse — null imageBase64 roundtrip`() {
            val res = NetworkProfileResponse("s", "r", "name", null)
            assertNull(json.decodeFromString(NetworkProfileResponse.serializer(), json.encodeToString(NetworkProfileResponse.serializer(), res)).imageBase64)
        }

        @Test
        fun `NetworkCallResponse — accepted flag roundtrip`() {
            listOf(true, false).forEach { accepted ->
                val res = NetworkCallResponse("s", "r", accepted)
                assertEquals(accepted, json.decodeFromString(NetworkCallResponse.serializer(), json.encodeToString(NetworkCallResponse.serializer(), res)).accepted)
            }
        }

        @Test
        fun `AccountEntity serialize — deserialize`() {
            val e = AccountEntity("peer123", 5555L)
            assertEquals(e, json.decodeFromString(AccountEntity.serializer(), json.encodeToString(AccountEntity.serializer(), e)))
        }

        @Test
        fun `ProfileEntity — null imageFileName`() {
            val e = ProfileEntity("p", 0L, "name", null)
            assertNull(json.decodeFromString(ProfileEntity.serializer(), json.encodeToString(ProfileEntity.serializer(), e)).imageFileName)
        }

        @Test
        fun `MessageEntity serialize — deserialize`() {
            val e = MessageEntity(1L, "s", "r", 1000L, MessageState.MESSAGE_READ, MessageType.TEXT_MESSAGE, "content")
            assertEquals(e, json.decodeFromString(MessageEntity.serializer(), json.encodeToString(MessageEntity.serializer(), e)))
        }

        @Test
        fun `json ignoreUnknownKeys — extra field does not throw`() {
            val raw = """{"peerId":"x","username":"U","shortCode":"XX","publicKeyHex":"k","ipAddress":null,"keepalive":0,"extraField":"surprise"}"""
            assertEquals("x", json.decodeFromString(NetworkDevice.serializer(), raw).peerId)
        }
    }
}