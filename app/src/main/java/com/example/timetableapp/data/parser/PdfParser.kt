package com.example.timetableapp.data.parser

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

object PdfParser {
    fun extractText(context: Context, uri: Uri): String {
        PDFBoxResourceLoader.init(context)
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Cannot open file: $uri")
        input.use { stream ->
            PDDocument.load(stream).use { doc ->
                return PDFTextStripper().apply { sortByPosition = true }.getText(doc)
            }
        }
    }

    /** Grid timetables (days as columns); null when pages hold no day grid. */
    fun parseGrid(context: Context, uri: Uri): GridTimetableParser.GridResult? {
        PDFBoxResourceLoader.init(context)
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Cannot open file: $uri")
        input.use { stream ->
            PDDocument.load(stream).use { doc ->
                val stripper = GridStripper()
                stripper.getText(doc) // drives processTextPosition/endPage collection
                return GridTimetableParser.parse(stripper.pages)
            }
        }
    }
}
