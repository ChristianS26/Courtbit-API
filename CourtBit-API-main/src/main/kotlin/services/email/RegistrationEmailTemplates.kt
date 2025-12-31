package services.email

object RegistrationEmailTemplates {

    fun playerConfirmationHtml(
        playerName: String,
        partnerName: String?,
        tournamentName: String?,
        tournamentId: String,
        categoryName: String?,
        categoryId: Int,
        paidFor: String,
        method: String
    ): String {
        val tn = tournamentName ?: "Torneo $tournamentId"
        val cn = categoryName ?: "Categoría #$categoryId"
        val partner = partnerName?.let { "<p><b>Compañero(a):</b> $it</p>" } ?: ""
        val whoPaid = when (paidFor) {
            "2" -> "Cubre a ambos jugadores"
            else -> "Cubre solo al jugador que pagó"
        }

        return """
            <div style="font-family:Segoe UI,Roboto,Helvetica,Arial,sans-serif;max-width:560px;margin:0 auto;padding:24px;">
              <h2 style="margin:0 0 12px;">¡Inscripción confirmada! 🎾</h2>
              <p>Hola <b>$playerName</b>, tu inscripción ha sido confirmada.</p>
              <p><b>Torneo:</b> $tn</p>
              <p><b>Categoría:</b> $cn</p>
              $partner
              <p><b>Método:</b> $method</p>
              <p><b>Cobertura del pago:</b> $whoPaid</p>
              <hr style="border:none;border-top:1px solid #e5e5e5;margin:16px 0;" />
              <p style="color:#666;font-size:12px;">Si tienes dudas, contactate a través de Whatsapp con un administrador del torneo.</p>
            </div>
        """.trimIndent()
    }

    fun adminNotificationHtml(
        playerName: String,
        partnerName: String?,
        playerEmail: String,
        tournamentName: String?,
        tournamentId: String,
        categoryName: String?,
        categoryId: Int,
        paidFor: String,
        method: String
    ): String {
        val tn = tournamentName ?: "Torneo $tournamentId"
        val cn = categoryName ?: "Categoría #$categoryId"
        val partner = partnerName?.let { "<p><b>Compañero(a):</b> $it</p>" } ?: ""
        val whoPaid = when (paidFor) {
            "2" -> "Cubre a ambos jugadores"
            else -> "Cubre solo al jugador que pagó"
        }

        return """
            <div style="font-family:Segoe UI,Roboto,Helvetica,Arial,sans-serif;max-width:560px;margin:0 auto;padding:24px;">
              <h2 style="margin:0 0 12px;">Nueva inscripción confirmada</h2>
              <p><b>Jugador:</b> $playerName</p>
              <p><b>Correo:</b> $playerEmail</p>
              $partner
              <p><b>Torneo:</b> $tn</p>
              <p><b>Categoría:</b> $cn</p>
              <p><b>Método:</b> $method</p>
              <p><b>Cobertura del pago:</b> $whoPaid</p>
              <hr style="border:none;border-top:1px solid #e5e5e5;margin:16px 0;" />
              <p style="color:#666;font-size:12px;">ID Torneo: $tournamentId &nbsp;|&nbsp; ID Categoría: $categoryId</p>
            </div>
        """.trimIndent()
    }
}
