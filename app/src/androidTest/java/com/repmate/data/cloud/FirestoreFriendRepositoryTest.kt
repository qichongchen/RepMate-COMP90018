package com.repmate.data.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.repmate.BuildConfig
import com.example.repmate.data.auth.FirebaseAuthRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [FirestoreFriendRepository] against the Firestore and Auth emulators.
 *
 * ## Rewritten for the friend-request flow
 * These tests used to call `addFriend(uid)`, which put someone on your friend list unilaterally.
 * That method is gone: a friendship now needs the other person to agree, so it is **send a
 * request, then the recipient accepts**. Because this file stopped compiling when `addFriend` was
 * removed, every instrumented test in the module -- Room, DataStore, the safety worker, all of
 * them -- became unrunnable, not just these.
 *
 * ## How a friendship is set up here
 * The request document is written directly as the sender rather than through
 * [FirestoreFriendRepository.sendFriendRequest]. That is deliberate: `sendFriendRequest` resolves
 * the recipient through a `usernames/{name}` claim, and claiming a name means writing the claim
 * and the profile in one batch that satisfies the rules -- seeding all that would make these
 * tests mostly about name claims rather than friendships. Writing the request as the sender is
 * exactly what the rules allow (`allow create` where `fromUserId == auth.uid`), so the accept path
 * under test receives the same document it would in the app.
 *
 * ## What it needs to run
 * The Firestore and Auth emulators reachable on 127.0.0.1 (on a physical device, `adb reverse`
 * those ports), and TEST_ACCOUNT_A_* / TEST_ACCOUNT_B_* set in `local.properties`.
 */
@RunWith(AndroidJUnit4::class)
class FirestoreFriendRepositoryTest {

    companion object {
        private lateinit var firebaseAuth: FirebaseAuth
        private lateinit var firestore: FirebaseFirestore

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            firebaseAuth = FirebaseAuth.getInstance()
            firestore = FirebaseFirestore.getInstance()

            firebaseAuth.useEmulator("127.0.0.1", 9099)
            firestore.useEmulator("127.0.0.1", 8080)
        }
    }

    // Supply disposable test credentials locally.
    private val accountAEmail = BuildConfig.TEST_ACCOUNT_A_EMAIL
    private val accountAPassword = BuildConfig.TEST_ACCOUNT_A_PASSWORD
    private val accountBEmail = BuildConfig.TEST_ACCOUNT_B_EMAIL
    private val accountBPassword = BuildConfig.TEST_ACCOUNT_B_PASSWORD
    private val accountBUid = BuildConfig.TEST_ACCOUNT_B_UID

    private lateinit var accountAUid: String

    @Before
    fun setup() = runBlocking {
        signInAsA()
        accountAUid = firebaseAuth.currentUser!!.uid
        clearFriendshipBetweenTheTestAccounts()
    }

    @Test
    fun acceptingARequestCreatesTheFriendshipForBothUsers() = runBlocking {
        sendRequestFromAToB()

        signInAsB()
        val accepted = repository().acceptFriendRequest(accountAUid)
        assertTrue("Failed to accept: ${accepted.exceptionOrNull()}", accepted.isSuccess)

        // B sees A...
        assertTrue(friendUids().contains(accountAUid))
        // ...and A sees B, the half the accepting user has to create on their behalf.
        signInAsA()
        assertTrue(friendUids().contains(accountBUid))
    }

    @Test
    fun acceptingARequestConsumesIt() = runBlocking {
        sendRequestFromAToB()

        signInAsB()
        repository().acceptFriendRequest(accountAUid)

        assertFalse(requestFromAExists())
    }

    @Test
    fun rejectingARequestRemovesItAndCreatesNoFriendship() = runBlocking {
        sendRequestFromAToB()

        signInAsB()
        val rejected = repository().rejectFriendRequest(accountAUid)
        assertTrue("Failed to reject: ${rejected.exceptionOrNull()}", rejected.isSuccess)

        assertFalse(requestFromAExists())
        assertFalse(friendUids().contains(accountAUid))
    }

    @Test
    fun aPendingRequestIsVisibleToTheRecipientOnly() = runBlocking {
        sendRequestFromAToB()

        // The sender cannot list the recipient's requests...
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, codeFromListingBsRequests())

        // ...but the recipient sees it.
        signInAsB()
        val requests = withTimeout(10_000) { repository().observeFriendRequests().first { it.isNotEmpty() } }
        assertEquals(listOf(accountAUid), requests.map { it.userId })
    }

    @Test
    fun removeFriendDeletesBothSides() = runBlocking {
        sendRequestFromAToB()
        signInAsB()
        repository().acceptFriendRequest(accountAUid)

        val removed = repository().removeFriend(accountAUid)
        assertTrue("Failed to remove: ${removed.exceptionOrNull()}", removed.isSuccess)

        assertFalse(friendUids().contains(accountAUid))
        // The other side goes too, so nobody is left with a one-sided friendship.
        signInAsA()
        assertFalse(friendUids().contains(accountBUid))
    }

    @Test
    fun aRequestForANameNobodyHasIsRefused() = runBlocking {
        val result = repository().sendFriendRequest("no_such_person_12345")

        assertTrue(result.isFailure)
        assertEquals("User not found", result.exceptionOrNull()?.message)
    }

    @Test
    fun aBlankNameIsRefused() = runBlocking {
        val result = repository().sendFriendRequest("   ")

        assertTrue(result.isFailure)
    }

    @Test
    fun userCannotReadAnotherUsersFriendList() = runBlocking {
        signInAsB()

        try {
            firestore
                .collection("users")
                .document(accountAUid)
                .collection("friends")
                .get()
                .await()

            throw AssertionError("Account B should not be able to read Account A's friend list")
        } catch (e: FirebaseFirestoreException) {
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
        }
    }

    @After
    fun tearDown() {
        firebaseAuth.signOut()
    }

    // --- helpers ---------------------------------------------------------------------------

    /** A repository bound to whoever is signed in right now. */
    private fun repository(): FirestoreFriendRepository =
        FirestoreFriendRepository(
            firestore = firestore,
            authRepository = FirebaseAuthRepository(firebaseAuth),
        )

    private suspend fun signInAsA() {
        firebaseAuth.signOut()
        firebaseAuth.signInWithEmailAndPassword(accountAEmail, accountAPassword).await()
    }

    private suspend fun signInAsB() {
        firebaseAuth.signOut()
        firebaseAuth.signInWithEmailAndPassword(accountBEmail, accountBPassword).await()
    }

    /**
     * The request document A would have created by sending one, written as A.
     *
     * See the class comment for why this is not routed through `sendFriendRequest`.
     */
    private suspend fun sendRequestFromAToB() {
        firestore
            .collection("users")
            .document(accountBUid)
            .collection("friendRequests")
            .document(accountAUid)
            .set(
                mapOf(
                    "fromUserId" to accountAUid,
                    "displayName" to "Account A",
                    "createdAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
    }

    /** The signed-in user's friend uids. */
    private suspend fun friendUids(): List<String> =
        firestore
            .collection("users")
            .document(firebaseAuth.currentUser!!.uid)
            .collection("friends")
            .get()
            .await()
            .documents
            .map { it.id }

    private suspend fun requestFromAExists(): Boolean =
        firestore
            .collection("users")
            .document(accountBUid)
            .collection("friendRequests")
            .document(accountAUid)
            .get()
            .await()
            .exists()

    private suspend fun codeFromListingBsRequests(): FirebaseFirestoreException.Code? =
        try {
            firestore
                .collection("users")
                .document(accountBUid)
                .collection("friendRequests")
                .get()
                .await()
            null
        } catch (e: FirebaseFirestoreException) {
            e.code
        }

    /** Leaves no friendship or pending request from an earlier test behind. */
    private suspend fun clearFriendshipBetweenTheTestAccounts() {
        runCatching {
            firestore
                .collection("users")
                .document(accountAUid)
                .collection("friends")
                .document(accountBUid)
                .delete()
                .await()
        }
        signInAsB()
        runCatching {
            firestore
                .collection("users")
                .document(accountBUid)
                .collection("friends")
                .document(accountAUid)
                .delete()
                .await()
        }
        runCatching {
            firestore
                .collection("users")
                .document(accountBUid)
                .collection("friendRequests")
                .document(accountAUid)
                .delete()
                .await()
        }
        signInAsA()
    }
}
