package models.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,

    @SerialName("first_name")
    val firstName: String,

    @SerialName("last_name")
    val lastName: String,

    val phone: String?,           // ← E.164 desde el cliente (ej. +525551234567)
    val gender: String?,

    val birthdate: String? = null,

    @SerialName("country_iso")
    val countryIso: String? = null,  // ← (ej. "MX")

    @SerialName("shirt_size")
    val shirtSize: String? = null,
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()

        if (email.isBlank() || !email.contains("@")) errors.add("Email inválido.")
        if (password.length < 8) errors.add("La contraseña debe tener al menos 8 caracteres.")
        if (firstName.isBlank()) errors.add("El nombre es obligatorio.")
        if (lastName.isBlank()) errors.add("El apellido es obligatorio.")

        // Validación básica de countryIso
        countryIso?.let {
            if (it.length != 2 || it.uppercase() != it) {
                errors.add("countryIso inválido (usa ISO-3166 de 2 letras en mayúsculas).")
            }
        }

        // Validación muy básica de E.164 (opcional)
        phone?.let {
            if (!it.startsWith("+") || it.length < 9) {
                errors.add("Teléfono inválido: usa formato internacional E.164.")
            }
        }

        return errors
    }
}
