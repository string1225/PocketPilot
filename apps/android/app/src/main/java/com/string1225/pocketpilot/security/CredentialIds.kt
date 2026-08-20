package com.string1225.pocketpilot.security

object CredentialIds {
    const val DEFAULT_LLM = "llm.default"
    const val DEFAULT_GIT_TOKEN = "git.default.token"

    fun projectGitToken(projectId: String): String {
        require(PROJECT_ID.matches(projectId)) { "Invalid project id for Git credential" }
        return "git.project.$projectId.token"
    }

    private val PROJECT_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
}
