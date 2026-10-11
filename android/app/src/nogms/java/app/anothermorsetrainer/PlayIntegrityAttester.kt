package app.anothermorsetrainer

import android.content.Context

/**
 * The `-Pnogms=true` build's stand-in for the real
 * [PlayIntegrityAttester][app.anothermorsetrainer.PlayIntegrityAttester] in
 * `src/play/java` (see the toggle at the top of `app/build.gradle.kts`). That
 * build ships no `com.google.android.play:integrity`, so the real one has
 * nothing to compile against; this exists so the rest of the app — which
 * names the type in [LeaderboardClient] — still compiles, and for no other
 * reason.
 *
 * **Nothing ever constructs it.** `LeaderboardClient.init` creates an attester
 * only `if (isConfigured)`, and a no-GMS build has
 * `BuildConfig.LEADERBOARD_CLOUD_PROJECT_NUMBER == 0`, so `isConfigured` is
 * false: the leaderboard screen, the opt-in dialog, the Settings rows and
 * `BuddyClient`'s two attested calls all take their existing "not configured"
 * path instead. [token] throwing is therefore the report of a bug in that
 * gating, not a state a user can reach — which is why it throws rather than
 * returning a token-shaped lie the server would have to reject.
 *
 * Deleting this file does not remove a feature; it breaks the no-GMS build.
 */
class PlayIntegrityAttester(context: Context, cloudProjectNumber: Long) {

    /** Always throws: this build cannot attest. See the class note. */
    suspend fun token(requestHash: String): String =
        throw UnsupportedOperationException(
            "built without Google Play Services: there is no Play Integrity to attest with"
        )
}
