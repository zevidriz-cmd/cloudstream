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
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentLoginBinding
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import androidx.navigation.fragment.findNavController
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.ui.setup.HAS_DONE_SETUP_KEY
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.lagradost.cloudstream3.syncproviders.AccountManager

class LoginFragment : Fragment() {
    companion object {
        const val TAG = "LoginFragment"
    }

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient

    private val googleSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)!!
                firebaseAuthWithGoogle(account.idToken!!)
            } catch (e: ApiException) {
                logError(e)
                Toast.makeText(context, "Google sign in failed", Toast.LENGTH_SHORT).show()
                setLoading(false)
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        auth = FirebaseAuth.getInstance()

        if (com.lagradost.cloudstream3.ui.settings.Globals.isLayout(com.lagradost.cloudstream3.ui.settings.Globals.TV)) {
            val code = generatePairingCode()
            createPairingDocument(code)
            displayQrCode(code)
            startPairingListener(code)
        }

        // Configure Google Sign In
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso)

        binding.loginGoogleButton.setOnClickListener {
            setLoading(true)
            val signInIntent = googleSignInClient.signInIntent
            googleSignInLauncher.launch(signInIntent)
        }

        binding.loginSignInButton.setOnClickListener {
            val email = binding.loginEmailInput.text.toString().trim()
            val password = binding.loginPasswordInput.text.toString().trim()
            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(context, "Please enter email and password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            setLoading(true)
            auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(requireActivity()) { task ->
                    if (task.isSuccessful) {
                        context?.let { ctx ->
                            ctx.setKey("firebase_email", email)
                            ctx.setKey("firebase_password", password)
                        }
                        onLoginSuccess()
                    } else {
                        // If sign in fails, try to sign up
                        auth.createUserWithEmailAndPassword(email, password)
                            .addOnCompleteListener(requireActivity()) { signUpTask ->
                                if (signUpTask.isSuccessful) {
                                    context?.let { ctx ->
                                        ctx.setKey("firebase_email", email)
                                        ctx.setKey("firebase_password", password)
                                    }
                                    onLoginSuccess()
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Authentication failed.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    setLoading(false)
                                }
                            }
                    }
                }
        }

        binding.loginSkipButton.setOnClickListener {
            // Navigate out without syncing
            if (requireContext().getKey<Boolean>(HAS_DONE_SETUP_KEY, false) != true) {
                findNavController().navigate(R.id.action_navigation_login_to_navigation_setup_language)
            } else {
                activity?.onBackPressed()
            }
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(requireActivity()) { task ->
                if (task.isSuccessful) {
                    onLoginSuccess()
                } else {
                    Toast.makeText(context, "Authentication Failed.", Toast.LENGTH_SHORT).show()
                    setLoading(false)
                }
            }
    }

    private fun onLoginSuccess() {
        val ctx = context ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            AccountManager.firebaseApi.syncLocalToFirestore(ctx)
            
            withContext(Dispatchers.Main) {
                setLoading(false)
                Toast.makeText(ctx, "Signed in successfully!", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "onLoginSuccess: Navigating after successful login")
                try {
                    // Navigate to appropriate next step
                    if (ctx.getKey<Boolean>(HAS_DONE_SETUP_KEY, false) != true) {
                        findNavController().navigate(R.id.action_navigation_login_to_navigation_setup_language)
                    } else {
                        // Try local action first, fall back to global action
                        try {
                            findNavController().navigate(R.id.action_navigation_login_to_navigation_profile_selector)
                        } catch (e: Exception) {
                            Log.w(TAG, "onLoginSuccess: Local nav action failed, trying global", e)
                            findNavController().navigate(R.id.global_to_navigation_profile_selector)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "onLoginSuccess: All navigation attempts failed, going home", e)
                    try {
                        findNavController().navigate(R.id.global_to_navigation_home)
                    } catch (e2: Exception) {
                        Log.e(TAG, "onLoginSuccess: Even global home nav failed", e2)
                        activity?.onBackPressed()
                    }
                }
            }
        }
    }

    private fun setLoading(isLoading: Boolean) {
        binding.loginLoading.isVisible = isLoading
        binding.loginGoogleButton.isEnabled = !isLoading
        binding.loginSignInButton.isEnabled = !isLoading
        binding.loginEmailInput.isEnabled = !isLoading
        binding.loginPasswordInput.isEnabled = !isLoading
    }

    private fun generatePairingCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..6)
            .map { chars.random() }
            .joinToString("")
    }

    private fun createPairingDocument(code: String) {
        val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        val data = hashMapOf(
            "status" to "pending",
            "createdAt" to System.currentTimeMillis()
        )
        firestore.collection("pairing_codes")
            .document(code)
            .set(data)
            .addOnFailureListener { e ->
                logError(e)
            }
    }

    private fun displayQrCode(code: String) {
        val qrData = "cloudstreamapp://pair?code=$code"
        val qrUrl = "https://api.qrserver.com/v1/create-qr-code/?size=300x300&data=" + android.net.Uri.encode(qrData)
        
        val density = resources.displayMetrics.density
        val sizePx = (200 * density).toInt()
        binding.loginLogo.layoutParams.width = sizePx
        binding.loginLogo.layoutParams.height = sizePx
        binding.loginLogo.requestLayout()
        
        binding.loginLogo.loadImage(qrUrl)
        
        binding.loginTitle.text = "Pair your device"
        binding.loginSubtitle.text = "Scan this QR code with your phone, or enter this code in Settings -> Pair TV:\n\nCode: $code"
    }

    private var pairingListener: com.google.firebase.firestore.ListenerRegistration? = null

    private fun startPairingListener(code: String) {
        val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        Log.d(TAG, "startPairingListener: Listening on pairing_codes/$code")
        pairingListener = firestore.collection("pairing_codes")
            .document(code)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e(TAG, "startPairingListener: Snapshot error", e)
                    logError(e)
                    return@addSnapshotListener
                }
                
                if (snapshot != null && snapshot.exists()) {
                    val status = snapshot.getString("status")
                    Log.d(TAG, "startPairingListener: Document status='$status'")
                    if (status == "authorized") {
                        val email = snapshot.getString("email")
                        val password = snapshot.getString("password")
                        val googleIdToken = snapshot.getString("googleIdToken")
                        Log.d(TAG, "startPairingListener: authorized — email=${email != null}, password=${password != null}, googleIdToken=${googleIdToken != null}")
                        
                        if (!googleIdToken.isNullOrBlank()) {
                            stopPairingListener()
                            loginWithGoogleIdToken(googleIdToken, code)
                        } else if (!email.isNullOrBlank() && !password.isNullOrBlank()) {
                            stopPairingListener()
                            loginWithCredentials(email, password, code)
                        } else {
                            Log.w(TAG, "startPairingListener: authorized but no usable credentials found")
                        }
                    }
                } else {
                    Log.d(TAG, "startPairingListener: Document does not exist or is null")
                }
            }
    }

    private fun stopPairingListener() {
        pairingListener?.remove()
        pairingListener = null
    }

    private fun loginWithGoogleIdToken(idToken: String, code: String) {
        val act = activity ?: return
        Log.d(TAG, "loginWithGoogleIdToken: Attempting sign-in with Google ID token for code=$code")
        setLoading(true)
        val credential = com.google.firebase.auth.GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(act) { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "loginWithGoogleIdToken: Sign-in successful, uid=${auth.currentUser?.uid}")
                    val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    firestore.collection("pairing_codes").document(code).delete()
                    onLoginSuccess()
                } else {
                    Log.e(TAG, "loginWithGoogleIdToken: Sign-in FAILED", task.exception)
                    Toast.makeText(
                        context,
                        "Google token pairing failed. Please set an email & password in your account to pair TV.",
                        Toast.LENGTH_LONG
                    ).show()
                    setLoading(false)
                }
            }
    }

    private fun loginWithCredentials(email: String, password: String, code: String) {
        val act = activity ?: return
        Log.d(TAG, "loginWithCredentials: Attempting sign-in with email=$email for code=$code")
        setLoading(true)
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(act) { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "loginWithCredentials: Sign-in successful, uid=${auth.currentUser?.uid}")
                    context?.let { ctx ->
                        ctx.setKey("firebase_email", email)
                        ctx.setKey("firebase_password", password)
                    }
                    val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    firestore.collection("pairing_codes").document(code).delete()
                    try {
                        onLoginSuccess()
                    } catch (e: Exception) {
                        Log.e(TAG, "loginWithCredentials: onLoginSuccess navigation crashed", e)
                        Toast.makeText(context, "Signed in successfully!", Toast.LENGTH_SHORT).show()
                        setLoading(false)
                    }
                } else {
                    Log.d(TAG, "loginWithCredentials: Sign-in failed, trying createUser", task.exception)
                    auth.createUserWithEmailAndPassword(email, password)
                        .addOnCompleteListener(act) { signUpTask ->
                            if (signUpTask.isSuccessful) {
                                Log.d(TAG, "loginWithCredentials: createUser successful, uid=${auth.currentUser?.uid}")
                                context?.let { ctx ->
                                    ctx.setKey("firebase_email", email)
                                    ctx.setKey("firebase_password", password)
                                }
                                val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                firestore.collection("pairing_codes").document(code).delete()
                                try {
                                    onLoginSuccess()
                                } catch (e: Exception) {
                                    Log.e(TAG, "loginWithCredentials: onLoginSuccess navigation crashed after createUser", e)
                                    Toast.makeText(context, "Signed in successfully!", Toast.LENGTH_SHORT).show()
                                    setLoading(false)
                                }
                            } else {
                                Log.e(TAG, "loginWithCredentials: createUser FAILED", signUpTask.exception)
                                Toast.makeText(context, "Pairing login failed.", Toast.LENGTH_SHORT).show()
                                setLoading(false)
                            }
                        }
                }
            }
    }

    override fun onDestroyView() {
        stopPairingListener()
        super.onDestroyView()
        _binding = null
    }
}
