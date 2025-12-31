package models.auth

class ValidationException(
    val errors: List<FieldError>
) : RuntimeException("Validation failed")
