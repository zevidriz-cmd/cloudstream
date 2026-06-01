package com.lagradost.cloudstream3.ui.sync

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentPairTvBinding
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.DataStore.getKey
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch

class FragmentPairTv : Fragment() {
    private var _binding: FragmentPairTvBinding? = null
    private val binding get() = _binding!!

    // Stores the pairing code so we can use it after interactive sign-in completes
    private var pendingPairingCode: String? = null

    // Interactive Google Sign-In launcher (fallback when silentSignIn fails)
    private val googleSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)!!
                val code = pendingPairingCode
                val idToken = account.idToken
                if (code != null && !idToken.isNullOrBlank()) {
                    // We got a fresh token, now complete the pairing
                    completePairing(code, idToken, account.email)
                } else {
                    Toast.makeText(context, "Google sign in did not return a token.", Toast.LENGTH_SHORT).show()
                    setLoading(false)
                }
            } catch (e: ApiException) {
                logError(e)
                Toast.makeText(context, "Google sign in was cancelled or failed.", Toast.LENGTH_SHORT).show()
                setLoading(false)
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPairTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.pairBackBtn.setOnClickListener {
            activity?.onBackPressed()
        }

        binding.pairSubmitButton.setOnClickListener {
            val code = TvPairing.normalizeCode(binding.pairCodeInput.text.toString())
            if (code == null) {
                Toast.makeText(context, "Pairing code must be exactly 6 characters.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            submitPairingCode(code)
        }
    }

    private fun submitPairingCode(rawCode: String) {
        val ctx = context ?: return

        val code = TvPairing.normalizeCode(rawCode)
        if (code == null) {
            Toast.makeText(ctx, "Invalid pairing code.", Toast.LENGTH_SHORT).show()
            return
        }

        val email = ctx.getKey<String>("firebase_email")
        val password = ctx.getKey<String>("firebase_password")

        setLoading(true)

        lifecycleScope.launch {
            try {
                val user = FirebaseAuth.getInstance().currentUser
                if (user == null) {
                    Toast.makeText(ctx, "Please log in first to pair TV.", Toast.LENGTH_LONG).show()
                    setLoading(false)
                    return@launch
                }

                // If we have email/password credentials, use those directly
                if (!email.isNullOrBlank() && !password.isNullOrBlank()) {
                    completePairingWithCredentials(code, email, password)
                    return@launch
                }

                // Otherwise try to get a Google ID token
                if (user.email != null) {
                    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                        .requestIdToken(ctx.getString(R.string.default_web_client_id))
                        .requestEmail()
                        .build()
                    val googleSignInClient = GoogleSignIn.getClient(ctx, gso)
                    
                    googleSignInClient.silentSignIn().addOnSuccessListener { account ->
                        val idToken = account.idToken
                        if (idToken.isNullOrBlank()) {
                            startInteractiveGooglePairing(code)
                        } else {
                            completePairing(code, idToken, account.email)
                        }
                    }.addOnFailureListener { e ->
                        logError(e)
                        startInteractiveGooglePairing(code)
                    }
                } else {
                    Toast.makeText(ctx, "Please log in using email/password first to pair TV.", Toast.LENGTH_LONG).show()
                    setLoading(false)
                }

            } catch (e: Exception) {
                logError(e)
                Toast.makeText(ctx, "Pairing error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                setLoading(false)
            }
        }
    }

    private fun startInteractiveGooglePairing(code: String) {
        val ctx = context ?: return
        pendingPairingCode = code
        val interactiveGso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(ctx.getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        val interactiveClient = GoogleSignIn.getClient(ctx, interactiveGso)
        googleSignInLauncher.launch(interactiveClient.signInIntent)
    }

    /** Complete pairing using a Google ID token */
    private fun completePairing(code: String, googleIdToken: String, email: String?) {
        lifecycleScope.launch {
            try {
                val ctx = context ?: return@launch
                val firestore = FirebaseFirestore.getInstance()
                val docRef = firestore.collection(TvPairing.COLLECTION).document(code)
                Log.d("FragmentPairTv", "completePairing: Fetching pairing doc for code=$code")
                val snapshot = docRef.get().await()

                if (!snapshot.exists()) {
                    Log.w("FragmentPairTv", "completePairing: Document does not exist for code=$code")
                    Toast.makeText(ctx, "Invalid or expired pairing code.", Toast.LENGTH_SHORT).show()
                    setLoading(false)
                    return@launch
                }

                val createdAt = snapshot.getLong("createdAt") ?: 0L
                val status = snapshot.getString("status")
                val age = Math.abs(System.currentTimeMillis() - createdAt)

                if (status != "pending" || age > 30 * 60 * 1000) {
                    Log.w("FragmentPairTv", "completePairing: Code expired or already used — status=$status, age=${age}ms")
                    Toast.makeText(ctx, "Pairing code has expired or is already paired.", Toast.LENGTH_SHORT).show()
                    setLoading(false)
                    return@launch
                }

                // Ensure email is never null — fall back to current Firebase user email
                val resolvedEmail = email ?: FirebaseAuth.getInstance().currentUser?.email ?: ""

                val updateData = hashMapOf<String, Any>(
                    "status" to "authorized",
                    "googleIdToken" to googleIdToken,
                    "email" to resolvedEmail
                )

                Log.d("FragmentPairTv", "completePairing: Updating doc with status=authorized, email=$resolvedEmail")
                docRef.update(updateData).await()
                Log.d("FragmentPairTv", "completePairing: Firestore update successful for code=$code")

                Toast.makeText(ctx, "Pairing approved. Waiting for TV sign-in...", Toast.LENGTH_SHORT).show()
                if (TvPairing.waitForTvCompletion(docRef)) {
                    Toast.makeText(ctx, "TV paired successfully!", Toast.LENGTH_LONG).show()
                    activity?.onBackPressed()
                } else {
                    Toast.makeText(ctx, "TV did not complete sign-in. Please try a new code.", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                logError(e)
                Log.e("FragmentPairTv", "completePairing: Error during pairing", e)
                Toast.makeText(context, "An error occurred during pairing.", Toast.LENGTH_SHORT).show()
            } finally {
                setLoading(false)
            }
        }
    }

    /** Complete pairing using email/password credentials */
    private fun completePairingWithCredentials(code: String, email: String, password: String) {
        lifecycleScope.launch {
            try {
                val ctx = context ?: return@launch
                val firestore = FirebaseFirestore.getInstance()
                val docRef = firestore.collection(TvPairing.COLLECTION).document(code)
                Log.d("FragmentPairTv", "completePairingWithCredentials: Fetching doc for code=$code")
                val snapshot = docRef.get().await()

                if (!snapshot.exists()) {
                    Log.w("FragmentPairTv", "completePairingWithCredentials: Document does not exist for code=$code")
                    Toast.makeText(ctx, "Invalid or expired pairing code.", Toast.LENGTH_SHORT).show()
                    setLoading(false)
                    return@launch
                }

                val createdAt = snapshot.getLong("createdAt") ?: 0L
                val status = snapshot.getString("status")
                val age = Math.abs(System.currentTimeMillis() - createdAt)

                if (status != "pending" || age > 30 * 60 * 1000) {
                    Log.w("FragmentPairTv", "completePairingWithCredentials: Code expired or used — status=$status, age=${age}ms")
                    Toast.makeText(ctx, "Pairing code has expired or is already paired.", Toast.LENGTH_SHORT).show()
                    setLoading(false)
                    return@launch
                }

                val updateData = hashMapOf<String, Any>(
                    "status" to "authorized",
                    "email" to email,
                    "password" to password
                )

                Log.d("FragmentPairTv", "completePairingWithCredentials: Updating doc with status=authorized, email=$email")
                docRef.update(updateData).await()
                Log.d("FragmentPairTv", "completePairingWithCredentials: Firestore update successful for code=$code")

                Toast.makeText(ctx, "Pairing approved. Waiting for TV sign-in...", Toast.LENGTH_SHORT).show()
                if (TvPairing.waitForTvCompletion(docRef)) {
                    Toast.makeText(ctx, "TV paired successfully!", Toast.LENGTH_LONG).show()
                    activity?.onBackPressed()
                } else {
                    Toast.makeText(ctx, "TV did not complete sign-in. Please try a new code.", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                logError(e)
                Log.e("FragmentPairTv", "completePairingWithCredentials: Error during pairing", e)
                Toast.makeText(context, "Pairing error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(isLoading: Boolean) {
        val binding = _binding ?: return
        binding.pairLoading.isVisible = isLoading
        binding.pairCodeInput.isEnabled = !isLoading
        binding.pairSubmitButton.isEnabled = !isLoading
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
