package com.repmate.data.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.repmate.data.auth.FirebaseAuthRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.example.repmate.BuildConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirestoreFriendRepositoryTest {

    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var repository: FirestoreFriendRepository

    // Supply disposable test credentials locally.
    private val accountAEmail = BuildConfig.TEST_ACCOUNT_A_EMAIL
    private val accountAPassword = BuildConfig.TEST_ACCOUNT_A_PASSWORD
    private val accountBEmail = BuildConfig.TEST_ACCOUNT_B_EMAIL
    private val accountBPassword = BuildConfig.TEST_ACCOUNT_B_PASSWORD
    private val accountBUid = BuildConfig.TEST_ACCOUNT_B_UID

    @Before
    fun setup() = runBlocking {
        firebaseAuth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        firebaseAuth.signOut()

        firebaseAuth
            .signInWithEmailAndPassword(
                accountAEmail,
                accountAPassword
            )
            .await()

        repository = FirestoreFriendRepository(
            firestore = firestore,
            authRepository = FirebaseAuthRepository(firebaseAuth)
        )
    }

    @Test
    fun addFriendAndReadFriendList() = runBlocking {
        val result = repository.addFriend(accountBUid)
        assertTrue(result.isSuccess)

        val friends = withTimeout(10000) {
            repository.observeFriends().first {
                    list -> list.any { it.userId == accountBUid }
            }
        }

        assertTrue(friends.any { it.userId == accountBUid })

        val currentUid = firebaseAuth.currentUser!!.uid

        val document = firestore
            .collection("users")
            .document(currentUid)
            .collection("friends")
            .document(accountBUid)
            .get()
            .await()

        assertTrue(document.exists())
        assertEquals(
            accountBUid,
            document.getString("friendUserId")
        )
    }

    @Test
    fun cannotAddSelf() = runBlocking {
        val currentUid = firebaseAuth.currentUser!!.uid

        val result = repository.addFriend(currentUid)

        assertTrue(result.isFailure)
    }

    @Test
    fun cannotAddNonexistentUser() = runBlocking {
        val fakeUid = "this-user-does-not-exist"

        val result = repository.addFriend(fakeUid)

        assertTrue(result.isFailure)
    }

    @Test
    fun removeFriendDeletesDocument() = runBlocking {
        repository.addFriend(accountBUid)

        val removeResult = repository.removeFriend(accountBUid)
        assertTrue(removeResult.isSuccess)

        val currentUid = firebaseAuth.currentUser!!.uid

        val document = firestore
            .collection("users")
            .document(currentUid)
            .collection("friends")
            .document(accountBUid)
            .get()
            .await()

        assertTrue(!document.exists())
    }

    @Test
    fun addingSameFriendTwiceDoesNotCreateDuplicate() = runBlocking {
        assertTrue(repository.addFriend(accountBUid).isSuccess)
        assertTrue(repository.addFriend(accountBUid).isSuccess)

        val currentUid = firebaseAuth.currentUser!!.uid

        val snapshot = firestore
            .collection("users")
            .document(currentUid)
            .collection("friends")
            .whereEqualTo("friendUserId", accountBUid)
            .get()
            .await()

        assertEquals(1, snapshot.size())
    }

    @Test
    fun userCannotReadAnotherUsersFriendList() = runBlocking {
        val accountAUid = firebaseAuth.currentUser!!.uid

        firebaseAuth.signOut()

        firebaseAuth
            .signInWithEmailAndPassword(
                accountBEmail,
                accountBPassword
            )
            .await()

        try {
            firestore
                .collection("users")
                .document(accountAUid)
                .collection("friends")
                .get()
                .await()

            throw AssertionError("Account B should not be able to read Account A's friend list")

        } catch (e: FirebaseFirestoreException) {
            assertEquals(
                FirebaseFirestoreException.Code.PERMISSION_DENIED,
                e.code
            )
        }
    }

    @After
    fun tearDown() {
        firebaseAuth.signOut()
    }
}