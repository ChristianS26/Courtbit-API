package com.incodap.services.excel

import models.payments.PaymentReportRowDto
import models.registrationcode.RegistrationCodeWithTournamentInfo
import models.teams.TeamGroupByCategoryFullResponse
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

class ExcelService {

    fun generateTeamsExcel(
        tournamentName: String,
        teamsByCategory: List<TeamGroupByCategoryFullResponse>
    ): ByteArray {
        val workbook: Workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Inscripciones")
        var rowIndex = 0

        val headerStyle = workbook.createCellStyle().apply {
            setFont(workbook.createFont().apply {
                bold = true
                fontHeightInPoints = 14
            })
        }

        val paidStyle = workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.GREEN.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }

        val unpaidStyle = workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.RED.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }

        // Título del reporte
        val titleRow = sheet.createRow(rowIndex++)
        titleRow.createCell(0).apply {
            setCellValue("Registro de Inscripciones - $tournamentName")
            cellStyle = headerStyle
        }
        rowIndex++

        // 🔹 Respetar el ORDEN recibido en teamsByCategory (sin ordenar aquí)
        teamsByCategory.forEach { group ->
            val catRow = sheet.createRow(rowIndex++)
            catRow.createCell(0).apply {
                setCellValue("Categoría: ${group.categoryName}")
                cellStyle = headerStyle
            }

            // Encabezados (agregamos "Restricción")
            val headerRow = sheet.createRow(rowIndex++)
            headerRow.createCell(0).apply { setCellValue("Ranking");    cellStyle = headerStyle }
            headerRow.createCell(1).apply { setCellValue("Jugador 1");   cellStyle = headerStyle }
            headerRow.createCell(2).apply { setCellValue("Teléfono");    cellStyle = headerStyle }
            headerRow.createCell(3).apply { setCellValue("Jugador 2");   cellStyle = headerStyle }
            headerRow.createCell(4).apply { setCellValue("Teléfono");    cellStyle = headerStyle }
            headerRow.createCell(5).apply { setCellValue("Puntos");      cellStyle = headerStyle }
            headerRow.createCell(6).apply { setCellValue("Restricción"); cellStyle = headerStyle } // 🆕 nueva columna

            // Ordenamos equipos por puntos (desc) dentro de la categoría, como antes
            group.teams
                .sortedByDescending { it.playerAPoints + it.playerBPoints }
                .withIndex()
                .forEach { (index, team) ->
                    val row = sheet.createRow(rowIndex++)

                    row.createCell(0).setCellValue("${index + 1}°")

                    val cellA = row.createCell(1)
                    cellA.setCellValue(team.playerA.firstName + " " + team.playerA.lastName)
                    cellA.cellStyle = if (team.playerAPaid) paidStyle else unpaidStyle

                    row.createCell(2).setCellValue(team.playerA.phone ?: "")

                    val cellB = row.createCell(3)
                    cellB.setCellValue(team.playerB.firstName + " " + team.playerB.lastName)
                    cellB.cellStyle = if (team.playerBPaid) paidStyle else unpaidStyle

                    row.createCell(4).setCellValue(team.playerB.phone ?: "")

                    row.createCell(5).setCellValue((team.playerAPoints + team.playerBPoints).toDouble())

                    // 🆕 Restricción por equipo (ajusta el nombre del campo si en tu DTO se llama distinto)
                    row.createCell(6).setCellValue(team.restriction ?: "")
                }

            rowIndex++
        }

        // Ajustar anchos (agregamos columna 6 para Restricción)
        sheet.setColumnWidth(0, 3000) // Ranking
        sheet.setColumnWidth(1, 7000) // Nombre A
        sheet.setColumnWidth(2, 5000) // Teléfono A
        sheet.setColumnWidth(3, 7000) // Nombre B
        sheet.setColumnWidth(4, 5000) // Teléfono B
        sheet.setColumnWidth(5, 4000) // Puntos
        sheet.setColumnWidth(6, 9000) // Restricción 🆕

        val outputStream = ByteArrayOutputStream()
        workbook.write(outputStream)
        workbook.close()
        return outputStream.toByteArray()
    }

    fun generateRegistrationCodesExcel(
        codes: List<RegistrationCodeWithTournamentInfo>
    ): ByteArray {
        val workbook: Workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Códigos de Registro")
        var rowIndex = 0

        // Estilo normal de encabezado
        val headerStyle = workbook.createCellStyle().apply {
            setFont(workbook.createFont().apply {
                bold = true
                fontHeightInPoints = 13
                color = IndexedColors.BLACK.index
            })
        }

        // Estilo azul para títulos de sección (fondo azul, texto negro)
        val blueHeaderStyle = workbook.createCellStyle().apply {
            setFont(workbook.createFont().apply {
                bold = true
                fontHeightInPoints = 13
                color = IndexedColors.BLACK.index
            })
            fillForegroundColor = IndexedColors.LIGHT_CORNFLOWER_BLUE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER
        }

        // Helper para parsear fechas
        fun formatDate(date: String?): String {
            if (date.isNullOrBlank()) return ""
            return try {
                val parsed = OffsetDateTime.parse(date)
                parsed.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
            } catch (_: Exception) {
                date // fallback al original si falla el parseo
            }
        }

        // Agrupa por torneo (solo usados y con torneo)
        val codesByTournament = codes.filter { it.used && !it.tournaments?.name.isNullOrBlank() }
            .groupBy { it.tournaments?.name!! }

        // Título principal (mergea columnas 0 a 3)
        val titleRow = sheet.createRow(rowIndex++)
        titleRow.createCell(0).apply {
            setCellValue("Reporte de Códigos de Registro")
            cellStyle = headerStyle
        }
        sheet.addMergedRegion(CellRangeAddress(rowIndex - 1, rowIndex - 1, 0, 3))
        rowIndex++

        // ========================
        // Sección 1: Códigos usados por torneo
        // ========================
        if (codesByTournament.isNotEmpty()) {
            codesByTournament.forEach { (tournament, codesList) ->
                // Título de torneo (mergea columnas 0 a 3)
                val usedTitle = sheet.createRow(rowIndex++)
                usedTitle.createCell(0).apply {
                    setCellValue("Torneo: $tournament")
                    cellStyle = blueHeaderStyle
                }
                sheet.addMergedRegion(CellRangeAddress(rowIndex - 1, rowIndex - 1, 0, 3))

                // Encabezados
                val headerRow = sheet.createRow(rowIndex++)
                headerRow.createCell(0).apply { setCellValue("Código"); cellStyle = headerStyle }
                headerRow.createCell(1).apply { setCellValue("Creado por"); cellStyle = headerStyle }
                headerRow.createCell(2).apply { setCellValue("Usado por (email)"); cellStyle = headerStyle }
                headerRow.createCell(3).apply { setCellValue("Fecha de uso"); cellStyle = headerStyle }

                codesList.forEach { code ->
                    val row = sheet.createRow(rowIndex++)
                    row.createCell(0).setCellValue(code.code)
                    row.createCell(1).setCellValue(code.created_by_email)
                    row.createCell(2).setCellValue(code.used_by_email ?: "")
                    row.createCell(3).setCellValue(formatDate(code.used_at))
                }
                rowIndex++
            }
        }

        // ===========================
        // Sección 2: Códigos no usados
        // ===========================
        val unusedCodes = codes.filter { !it.used }
        if (unusedCodes.isNotEmpty()) {
            // Encabezado azul (mergea columnas 0 a 2)
            val unusedTitle = sheet.createRow(rowIndex++)
            unusedTitle.createCell(0).apply {
                setCellValue("Códigos NO usados")
                cellStyle = blueHeaderStyle
            }
            sheet.addMergedRegion(CellRangeAddress(rowIndex - 1, rowIndex - 1, 0, 2))

            val headerRow = sheet.createRow(rowIndex++)
            headerRow.createCell(0).apply { setCellValue("Código"); cellStyle = headerStyle }
            headerRow.createCell(1).apply { setCellValue("Creado por"); cellStyle = headerStyle }
            headerRow.createCell(2).apply { setCellValue("Fecha de creación"); cellStyle = headerStyle }

            unusedCodes.forEach { code ->
                val row = sheet.createRow(rowIndex++)
                row.createCell(0).setCellValue(code.code)
                row.createCell(1).setCellValue(code.created_by_email)
                row.createCell(2).setCellValue(formatDate(code.created_at))
            }
        }

        // Ajuste de anchos
        sheet.setColumnWidth(0, 4000)
        sheet.setColumnWidth(1, 8000)
        sheet.setColumnWidth(2, 8000)
        sheet.setColumnWidth(3, 7000)

        val outputStream = ByteArrayOutputStream()
        workbook.write(outputStream)
        workbook.close()
        return outputStream.toByteArray()
    }

    fun generatePaymentsReportExcel(
        tournamentName: String,
        rows: List<PaymentReportRowDto>
    ): ByteArray {
        val wb: Workbook = XSSFWorkbook()
        val sheet = wb.createSheet("Pagos")
        var r = 0

        val headerStyle = wb.createCellStyle().apply {
            setFont(wb.createFont().apply {
                bold = true
                fontHeightInPoints = 13
            })
        }
        val successStyle = wb.createCellStyle().apply {
            fillForegroundColor = IndexedColors.LIGHT_GREEN.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }
        val errorStyle = wb.createCellStyle().apply {
            fillForegroundColor = IndexedColors.ROSE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }
        val sectionHeaderStyle = wb.createCellStyle().apply {
            setFont(wb.createFont().apply {
                bold = true
                fontHeightInPoints = 12
                color = IndexedColors.BLACK.index
            })
            fillForegroundColor = IndexedColors.LIGHT_CORNFLOWER_BLUE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }
        val subtotalStyle = wb.createCellStyle().apply {
            setFont(wb.createFont().apply {
                bold = true
                fontHeightInPoints = 12
            })
        }
        // Entero sin decimales para Monto (mostrado en MXN)
        val intStyle = wb.createCellStyle().apply {
            dataFormat = wb.creationHelper.createDataFormat().getFormat("0")
        }

        // Título
        sheet.createRow(r++).createCell(0).apply {
            setCellValue("Reporte de Pagos - $tournamentName")
            cellStyle = headerStyle
        }
        r++

        // Encabezados (se repetirán por sección)
        fun writeHeaderRow(rowIndex: Int): Int {
            val header = sheet.createRow(rowIndex)
            listOf(
                "Fecha",
                "Método",
                "Estado",
                "Monto",             // mostrado en MXN entero
                "Jugador",
                "Email",
                "TeamId",
                "Categoría",
                "StripePaymentId",
                "AdminEmail"
            ).forEachIndexed { i, h ->
                header.createCell(i).apply { setCellValue(h); cellStyle = headerStyle }
            }
            return rowIndex + 1
        }

        // Helper fecha
        fun fmt(ts: String?): String {
            if (ts.isNullOrBlank()) return ""
            return try {
                val odt = OffsetDateTime.parse(ts)
                odt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
            } catch (_: Exception) {
                ts
            }
        }

        // Agrupar por categoría (nombre) con fallback
        val groups = rows
            .groupBy { it.category_name?.takeIf { n -> n.isNotBlank() } ?: "Sin categoría" }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)

        var grandTotalCentsSucceeded = 0L

        groups.forEach { (categoryName, list) ->
            // Encabezado de sección
            val sec = sheet.createRow(r++)
            sec.createCell(0).apply {
                setCellValue("Categoría: $categoryName")
                cellStyle = sectionHeaderStyle
            }
            // Merge del título de sección sobre todas las columnas (0..9)
            sheet.addMergedRegion(CellRangeAddress(r - 1, r - 1, 0, 9))

            // Encabezados
            r = writeHeaderRow(r)

            // Filas
            var subtotalCentsSucceeded = 0L

            list.forEach { row ->
                val data = sheet.createRow(r++)
                data.createCell(0).setCellValue(fmt(row.paid_at))
                data.createCell(1).setCellValue(row.method)
                data.createCell(2).apply {
                    setCellValue(row.status)
                    cellStyle = if (row.status.equals("succeeded", true)) successStyle else errorStyle
                }

                // amount en centavos -> entero MXN
                val amountCents = row.amount.toLong()
                val amountIntMx = Math.round(amountCents / 100.0).toInt()
                data.createCell(3).apply {
                    setCellValue(amountIntMx.toDouble())
                    cellStyle = intStyle
                }

                if (row.status.equals("succeeded", true)) {
                    subtotalCentsSucceeded += amountCents
                }

                data.createCell(4).setCellValue(row.player_full_name ?: "")
                data.createCell(5).setCellValue(row.player_email ?: "")
                data.createCell(6).setCellValue(row.team_id ?: "")
                data.createCell(7).setCellValue(row.category_name ?: "")
                data.createCell(8).setCellValue(row.stripe_payment_id ?: "")
                data.createCell(9).setCellValue(row.admin_email ?: "")
            }

            grandTotalCentsSucceeded += subtotalCentsSucceeded

            // Subtotal por categoría (solo succeeded)
            val subtotalIntMx = Math.round(subtotalCentsSucceeded / 100.0).toInt()
            val subRow = sheet.createRow(r++)
            subRow.createCell(2).apply { // Columna "Estado"
                setCellValue("Subtotal (aprobados)")
                cellStyle = subtotalStyle
            }
            subRow.createCell(3).apply { // Columna "Monto"
                setCellValue(subtotalIntMx.toDouble())
                cellStyle = intStyle
            }

            r++ // línea en blanco entre categorías
        }

        // Total general (solo succeeded)
        val grandIntMx = Math.round(grandTotalCentsSucceeded / 100.0).toInt()
        val totalRow = sheet.createRow(r++)
        totalRow.createCell(2).apply {
            setCellValue("TOTAL (aprobados)")
            cellStyle = headerStyle
        }
        totalRow.createCell(3).apply {
            setCellValue(grandIntMx.toDouble())
            cellStyle = intStyle
        }

        // Anchos
        sheet.setColumnWidth(0, 5200) // Fecha
        sheet.setColumnWidth(1, 3600) // Método
        sheet.setColumnWidth(2, 3600) // Estado
        sheet.setColumnWidth(3, 3200) // Monto
        sheet.setColumnWidth(4, 8000) // Jugador
        sheet.setColumnWidth(5, 8500) // Email
        sheet.setColumnWidth(6, 7000) // TeamId
        sheet.setColumnWidth(7, 5200) // Categoría
        sheet.setColumnWidth(8, 7800) // StripePaymentId
        sheet.setColumnWidth(9, 7800) // AdminEmail

        return ByteArrayOutputStream().use { out ->
            wb.write(out)
            wb.close()
            out.toByteArray()
        }
    }
}
