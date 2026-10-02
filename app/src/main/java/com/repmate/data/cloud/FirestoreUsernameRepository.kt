package com.repmate.data.cloud

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.Transaction
import com.repmate.data.local.DisplayNamePreferences
import com.repmate.data.repo.ClaimResult
import com.repmate.data.repo.ClaimStatus
import com.repmate.data.repo.DisplayNameRules
import com.repmate.data.repo.NameAvailability
import com.repmate.data.repo.NameCheck
import com.repmate.data.repo.UsernameRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [UsernameRepository] over Firestore, working with the deployed `firestore.rules`:
 * `usernames/{id}` (uid + serverTimestamp createdAt) is the claim, `users/{uid}.displayName` the
 * original-case name. See the rules for exactly what each write must satisfy.
 */
@Singleton
class FirestoreUsernameRepository
    @Inject
    constructor(
        private val firestore: FirebaseFirestore,
        private val firebaseAuth: FirebaseAuth,
        private val preferences: DisplayNamePreferences,
    ) : UsernameRepository {
        override fun authNameForSuggestion(): String? {
            val user = firebaseAuth.currentUser ?: return null
            return user.displayName?.takeIf { it.isNotBlank() }
                ?: user.providerData.firstNotNullOfOrNull { info -> info.displayName?.takeIf { it.isNotBlank() } }
        }

        override fun currentDisplayName(): String? = firebaseAuth.currentUser?.displayName?.takeIf { it.isNotBlank() }

        override suspend fun checkAvailability(name: String): NameAvailability {
            val check = DisplayNameRules.validate(name)
            if (check != NameCheck.VALID) return NameAvailability.Invalid(check)
            val uid = firebaseAuth.currentUser?.uid ?: return NameAvailability.Error
            return try {
                // Source.SERVER, never the cache: a stale cached "missing" must not say "available".
                val snapshot = usernameDoc(DisplayNameRules.idFor(name)).get(Source.SERVER).await()
                when {
                    !snapshot.exists() -> NameAvailability.Available
                    snapshot.getString("uid") == uid -> NameAvailability.Mine
                    else -> NameAvailability.Taken
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "availability check failed", e)
                NameAvailability.Error
            }
        }

        override suspend fun claim(name: String): ClaimResult {
            val check = DisplayNameRules.validate(name)
            if (check != NameCheck.VALID) return ClaimResult.Invalid(check)
            val user = firebaseAuth.currentUser ?: return ClaimResult.Error
            return try {
                // A guest who has just upgraded still carries an anonymous token until it is
                // refreshed, and the rules refuse anonymous claims. Force the refresh first.
                user.getIdToken(true).await()
                val written = firestore.runTransaction(claimTransaction(user.uid, name)).await()
                if (!written) return ClaimResult.Taken
                preferences.markClaimed(user.uid)
                syncAuthProfile(user, name)
                ClaimResult.Success
            } catch (e: CancellationException) {
                throw e
            } catch (e: FirebaseFirestoreException) {
                // A denied create means the name was taken in a race (the rules refuse a second
                // create on an existing id). Local validation and the token refresh above already
                // ruled out the other usual causes.
                if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    ClaimResult.Taken
                } else {
                    Log.w(TAG, "claim failed", e)
                    ClaimResult.Error
                }
            } catch (e: Exception) {
                Log.w(TAG, "claim failed", e)
                ClaimResult.Error
            }
        }

        /**
         * The claim/rename as one atomic transaction (not a batch: a transaction fails fast when
         * offline instead of queueing). Returns false if [name] belongs to someone else.
         *
         * All reads come first (Firestore requires it). Then, in the same commit:
         *  - set `usernames/{new}` only if it does not exist yet -- when it is already mine (a
         *    case-only change, or re-confirming a claim) the rules forbid rewriting it;
         *  - create `users/{uid}` with the new name if missing (a transaction cannot create and
         *    then update the same document), else update `displayName` if it changed;
         *  - delete `usernames/{old}` if it is mine and a different id, so a rename cannot hoard.
         */
        private fun claimTransaction(
            uid: String,
            name: String,
        ): Transaction.Function<Boolean> =
            Transaction.Function { transaction ->
                val newId = DisplayNameRules.idFor(name)
                val userRef = firestore.collection("users").document(uid)
                val newRef = usernameDoc(newId)

                val userSnapshot = transaction.get(userRef)
                val newSnapshot = transaction.get(newRef)
                val oldName = userSnapshot.getString("displayName")
                val oldId =
                    oldName
                        ?.takeIf { DisplayNameRules.validate(it) != NameCheck.INVALID_FORMAT }
                        ?.let { DisplayNameRules.idFor(it) }
                        ?.takeIf { it != newId }
                val oldRef = oldId?.let { usernameDoc(it) }
                val oldSnapshot = oldRef?.let { transaction.get(it) }

                if (newSnapshot.exists() && newSnapshot.getString("uid") != uid) return@Function false

                if (!newSnapshot.exists()) {
                    transaction.set(newRef, mapOf("uid" to uid, "createdAt" to FieldValue.serverTimestamp()))
                }
                if (!userSnapshot.exists()) {
                    transaction.set(userRef, mapOf("displayName" to name, "createdAt" to FieldValue.serverTimestamp()))
                } else if (oldName != name) {
                    transaction.update(userRef, "displayName", name)
                }
                if (oldRef != null && oldSnapshot != null && oldSnapshot.exists() && oldSnapshot.getString("uid") == uid) {
                    transaction.delete(oldRef)
                }
                true
            }

        override suspend fun claimStatus(): ClaimStatus {
            val user = firebaseAuth.currentUser ?: return ClaimStatus.UNKNOWN
            // A guest never claims; callers gate on anonymity first, but never gate on this.
            if (user.isAnonymous) return ClaimStatus.UNKNOWN
            return try {
                if (preferences.isClaimed(user.uid)) return ClaimStatus.CLAIMED
                val lookup = lookupClaim(user.uid)
                if (lookup.status == ClaimStatus.CLAIMED) preferences.markClaimed(user.uid)
                lookup.status
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ClaimStatus.UNKNOWN
            }
        }

        override suspend fun syncAuthNameIfClaimed() {
            val user = firebaseAuth.currentUser ?: return
            if (user.isAnonymous) return
            try {
                val lookup = lookupClaim(user.uid)
                val claimedName = lookup.name
                if (lookup.status != ClaimStatus.CLAIMED || claimedName == null) return
                preferences.markClaimed(user.uid)
                if (user.displayName != claimedName) syncAuthProfile(user, claimedName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "auth name sync failed", e)
            }
        }

        private data class ClaimLookup(
            val status: ClaimStatus,
            val name: String?,
        )

        /** Two server reads inside one short budget: the profile's name, then whether that name's claim is mine. */
        private suspend fun lookupClaim(uid: String): ClaimLookup =
            try {
                withTimeout(LOOKUP_TIMEOUT_MS) {
                    val profile = firestore.collection("users").document(uid).get(Source.SERVER).await()
                    val name = profile.getString("displayName")
                    if (!profile.exists() || name == null || DisplayNameRules.validate(name) == NameCheck.INVALID_FORMAT) {
                        ClaimLookup(ClaimStatus.NOT_CLAIMED, null)
                    } else {
                        val claim = usernameDoc(DisplayNameRules.idFor(name)).get(Source.SERVER).await()
                        if (claim.exists() && claim.getString("uid") == uid) {
                            ClaimLookup(ClaimStatus.CLAIMED, name)
                        } else {
                            ClaimLookup(ClaimStatus.NOT_CLAIMED, null)
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                ClaimLookup(ClaimStatus.UNKNOWN, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ClaimLookup(ClaimStatus.UNKNOWN, null)
            }

        /** Updates the Auth profile name and reloads, so the header and Home read it. Failure is non-fatal: the claim already succeeded. */
        private suspend fun syncAuthProfile(
            user: FirebaseUser,
            name: String,
        ) {
            try {
                user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name).build()).await()
                user.reload().await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "could not update the Auth profile name", e)
            }
        }

        private fun usernameDoc(id: String) = firestore.collection("usernames").document(id)

        private companion object {
            const val TAG = "UsernameRepository"

            /** Both reads of [lookupClaim] must finish within this, or the answer is UNKNOWN. */
            const val LOOKUP_TIMEOUT_MS = 3_000L
        }
    }
