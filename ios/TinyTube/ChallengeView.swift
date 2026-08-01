import SwiftUI
import TinyTubeCore

/* The arithmetic fallback, for a device with no lock at all.

   `Challenge` decides what the puzzle is and whether an answer is right, and is
   pure so it is tested on Linux. This only draws it.

   The numbers are re-rolled on every wrong answer, so guesses cannot converge
   on one puzzle by elimination. */
struct ChallengeView: View {

    let onPass: () -> Void
    let onCancel: () -> Void

    @State private var puzzle = Challenge.generate()
    @State private var x = ""
    @State private var y = ""
    @State private var wrong = false

    var body: some View {
        VStack(spacing: 20) {
            Text("Parent mode")
                .font(.title2.weight(.semibold))

            Text("This device has no screen lock, so answer this instead.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            VStack(spacing: 6) {
                Text("Two numbers add up to **\(puzzle.sum)**")
                Text("and differ by **\(puzzle.difference)**.")
            }
            .font(.body)
            .multilineTextAlignment(.center)

            HStack(spacing: 12) {
                field("Larger", text: $x)
                field("Smaller", text: $y)
            }

            if wrong {
                Text("Not quite — here's another one.")
                    .font(.footnote)
                    .foregroundStyle(.red)
            }

            HStack(spacing: 12) {
                Button("Cancel", action: onCancel)
                    .buttonStyle(.bordered)
                Button("Unlock", action: check)
                    .buttonStyle(.borderedProminent)
                    .disabled(x.isEmpty || y.isEmpty)
            }
            .padding(.top, 4)
        }
        .padding(28)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black)
    }

    private func field(_ label: String, text: Binding<String>) -> some View {
        VStack(spacing: 4) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            TextField("", text: text)
                .keyboardType(.numberPad)
                .multilineTextAlignment(.center)
                .textFieldStyle(.roundedBorder)
                .frame(width: 100)
        }
    }

    private func check() {
        if Challenge.isCorrect(puzzle, xInput: x, yInput: y) {
            onPass()
        } else {
            /* Re-rolled rather than retried. A puzzle that stays put while
               answers are tried against it is one a child can grind down. */
            puzzle = Challenge.generate()
            x = ""
            y = ""
            wrong = true
        }
    }
}
