package com.string1225.pocketpilot.security

interface SecureCredentialStore {
    /** Persists secret material under an opaque, non-secret identifier. */
    fun put(credentialId: String, secret: CharArray)

    /** Returns a caller-owned copy which must be zeroed immediately after use. */
    fun get(credentialId: String): CharArray?

    fun contains(credentialId: String): Boolean

    fun remove(credentialId: String)
}

class CredentialStoreException(
    val code: String,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
