package models.registrationcode

sealed class RegisterTeamResult {
    object Created : RegisterTeamResult()
    object Updated : RegisterTeamResult()
    object InvalidCode : RegisterTeamResult()
    object AlreadyRegistered : RegisterTeamResult()
}
