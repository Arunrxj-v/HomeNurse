package com.homenurse.core.logging

import android.util.Log

/**
 * Privacy-preserving logging.
 *
 * HomeNurse never logs medical information: no patient names, no document
 * contents, no OCR text, no medication names, no lab values, no conversations
 * and no AI prompts. Callers may only pass coarse, non-sensitive events such
 * as state transitions, counts and error categories.
 *
 * Exception logging records the exception class name only — messages and
 * stack traces are deliberately not written to logcat because they could
 * carry medical content.
 */
object PrivacyLog {

    private const val TAG = "HomeNurse"

    /** Log a coarse, non-sensitive event (e.g. "model_download_started"). */
    fun event(name: String) {
        Log.d(TAG, name)
    }

    /** Log a coarse warning. */
    fun warn(name: String) {
        Log.w(TAG, name)
    }

    /**
     * Log a failure by category + exception type. Never logs the exception
     * message, which could contain sensitive data.
     */
    fun failure(category: String, error: Throwable) {
        Log.e(TAG, "$category (${error.javaClass.simpleName})")
    }
}
