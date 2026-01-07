package models.users

sealed class DeleteUserResult {
    object Deleted : DeleteUserResult()
    object NotFound : DeleteUserResult()
    data class Error(val message: String) : DeleteUserResult()
}
