import Foundation
import LocalAuthentication

/* The door to parent mode. Counterpart of ChallengeActivity.

   THE GATE IS THE DEVICE LOCK. The arithmetic is a fallback for a device that
   has none, and the order must not be reversed: the arithmetic is beatable by
   any child who can do algebra, and it exists only so parent mode still has a
   door on a phone with no screen lock.

   `deviceOwnerAuthentication` — not `…WithBiometrics`. The plain policy falls
   back to the passcode by itself when Face ID fails or is unavailable, which is
   what makes the arithmetic nearly unreachable on iOS: a device has to have NO
   passcode at all to get there. That difference from Android is in README's
   Platform differences table.

   This app never invents or stores a secret of its own. It only learns whether
   the platform's check passed. */
enum Gate {

    enum Outcome {
        case passed
        /* No device lock at all, so there is nothing to authenticate against
           and the arithmetic has to stand in. */
        case needsChallenge
        case failed
    }

    /* Whether the device has any lock configured. Asked before prompting so
       the arithmetic appears instead of a system sheet that cannot succeed. */
    static func hasDeviceLock(_ context: LAContext = LAContext()) -> Bool {
        var error: NSError?
        return context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error)
    }

    static func authenticate(
        reason: String = "Unlock parent mode",
        context: LAContext = LAContext(),
        completion: @escaping (Outcome) -> Void
    ) {
        guard hasDeviceLock(context) else {
            completion(.needsChallenge)
            return
        }
        context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { ok, _ in
            /* Back to the main queue: LocalAuthentication answers on a private
               one, and every caller here is about to change what is on screen. */
            DispatchQueue.main.async { completion(ok ? .passed : .failed) }
        }
    }
}
