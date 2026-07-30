package dev.vtlinh.ytkids

import android.app.Activity
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/* The gate in front of parent mode.

   Answer X, given X+Y and X−Y. Correct answer finishes RESULT_OK and the
   caller opens parent mode; anything else re-rolls the numbers so a wrong
   guess can't be walked up by trying neighbours of the last question. */
class ChallengeActivity : AppCompatActivity() {

    private var puzzle = Challenge.generate()
    private lateinit var question: TextView
    private lateinit var answer: EditText
    private lateinit var error: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_challenge)
        title = getString(R.string.parent_mode)

        question = findViewById(R.id.question)
        answer = findViewById(R.id.answer)
        error = findViewById(R.id.error)

        render()

        findViewById<Button>(R.id.submit).setOnClickListener { check() }
        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }
        /* the keyboard's Done key should work as the button does — with a
           number pad up, reaching for the button is the awkward path */
        answer.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { check(); true } else false
        }
    }

    private fun render() {
        question.text = getString(R.string.challenge_question, puzzle.sum, puzzle.difference)
        answer.setText("")
    }

    private fun check() {
        if (Challenge.isCorrect(puzzle, answer.text?.toString().orEmpty())) {
            setResult(Activity.RESULT_OK)
            finish()
            return
        }
        /* New numbers on every miss. Without this, a wrong guess tells you the
           answer is nearby and the next few tries converge on it. */
        puzzle = Challenge.generate()
        error.text = getString(R.string.challenge_wrong)
        render()
    }
}
