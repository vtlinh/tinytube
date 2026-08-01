package dev.vtlinh.tinytube

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

/* The gate in front of parent mode.

   Parent mode is unrestricted YouTube in a WebView, which is exactly what the
   rest of the app exists to keep a child out of.

   The gate is the device's own lock — fingerprint, face, PIN, whatever the
   owner already uses. That is a real barrier rather than the speed bump the
   arithmetic was: a child who can do algebra got past the old one, and nothing
   about it improved with the child's age.

   It also means this app never invents, stores or can leak a secret of its
   own. The check is the platform's; we only learn whether it passed.

   The arithmetic survives as a fallback for a device with no lock set up at
   all, where there is nothing to authenticate against. That is a weaker gate,
   but the alternative on such a device is either no gate or no parent mode. */
class ChallengeActivity : AppCompatActivity() {

    private var puzzle = Challenge.generate()
    private lateinit var question: TextView
    private lateinit var answerX: EditText
    private lateinit var answerY: EditText
    private lateinit var error: TextView
    private lateinit var fallback: View

    /* Biometrics alone would lock out a parent whose fingerprint isn't
       recognised on a cold morning; the device credential is the way back in.
       WEAK rather than STRONG because face unlock on many devices is
       classified weak, and refusing it would send those users to a PIN every
       time for no gain — this gate is about intent, not about protecting a
       cryptographic key. */
    private val authenticators = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_challenge)
        title = getString(R.string.parent_mode)

        question = findViewById(R.id.question)
        answerX = findViewById(R.id.answer_x)
        answerY = findViewById(R.id.answer_y)
        error = findViewById(R.id.error)
        fallback = findViewById(R.id.fallback)

        findViewById<Button>(R.id.submit).setOnClickListener { check() }
        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }
        answerY.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { check(); true } else false
        }

        if (deviceLockAvailable()) promptForDeviceLock() else showArithmetic()
    }

    /* Whether there is anything to authenticate against. NO_HARDWARE and
       NONE_ENROLLED both mean "this device cannot answer the question", and on
       API 29 and below a WEAK+CREDENTIAL combination is not supported at all,
       which surfaces as UNSUPPORTED. All of them fall back. */
    private fun deviceLockAvailable(): Boolean = try {
        BiometricManager.from(this).canAuthenticate(authenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS
    } catch (e: Exception) {
        false
    }

    private fun promptForDeviceLock() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    setResult(Activity.RESULT_OK)
                    finish()
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    when (code) {
                        /* The parent backed out. Not a failure, and not a
                           reason to offer an easier way in. */
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED,
                        -> finish()
                        /* The device said it could authenticate and then
                           couldn't — locked out after too many attempts, or
                           hardware that failed to come up. Falling back beats
                           a dead end, and the arithmetic is still better than
                           nothing standing in front of real YouTube. */
                        else -> showArithmetic()
                    }
                }
            },
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.gate_title))
            .setSubtitle(getString(R.string.gate_subtitle))
            .setAllowedAuthenticators(authenticators)
            .build()

        try {
            prompt.authenticate(info)
        } catch (e: Exception) {
            showArithmetic()
        }
    }

    /* ---- fallback: the arithmetic ---- */

    private fun showArithmetic() {
        fallback.visibility = View.VISIBLE
        render()
        answerX.requestFocus()
    }

    private fun render() {
        question.text = getString(R.string.challenge_question, puzzle.sum, puzzle.difference)
        answerX.setText("")
        answerY.setText("")
    }

    private fun check() {
        if (Challenge.isCorrect(
                puzzle,
                answerX.text?.toString().orEmpty(),
                answerY.text?.toString().orEmpty(),
            )
        ) {
            setResult(Activity.RESULT_OK)
            finish()
            return
        }
        /* New numbers on every miss. Without this, a wrong guess tells you the
           answer is nearby and the next few tries converge on it. */
        puzzle = Challenge.generate()
        error.text = getString(R.string.challenge_wrong)
        render()
        answerX.requestFocus()
    }
}
