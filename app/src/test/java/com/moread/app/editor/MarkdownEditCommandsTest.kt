package com.moread.app.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownEditCommandsTest {

    @Test
    fun boldWrapsSelection() {
        val result = MarkdownEditCommands.bold("hello world", 6, 11)
        assertEquals("hello **world**", result.text)
        assertEquals(8, result.selectionStart)
        assertEquals(13, result.selectionEnd)
    }

    @Test
    fun boldTogglesOffWhenSelectionAlreadyWrapped() {
        val result = MarkdownEditCommands.bold("hello **world**", 8, 13)
        assertEquals("hello world", result.text)
    }

    @Test
    fun boldWithEmptySelectionInsertsPair() {
        val result = MarkdownEditCommands.bold("abc", 1, 1)
        assertEquals("a****bc", result.text)
        assertEquals(3, result.selectionStart)
    }

    @Test
    fun italicEmptySelectionPlacesCursorInside() {
        val result = MarkdownEditCommands.italic("ab", 2, 2)
        assertEquals("ab**", result.text)
        assertEquals(3, result.selectionStart)
        assertEquals(3, result.selectionEnd)
    }

    @Test
    fun headingTogglesCurrentLine() {
        val added = MarkdownEditCommands.heading("title\nbody", 2, 2, level = 2)
        assertEquals("## title\nbody", added.text)

        val removed = MarkdownEditCommands.heading("## title\nbody", 2, 2, level = 2)
        assertEquals("title\nbody", removed.text)
    }

    @Test
    fun headingOnEmptyLineInsertsPrefix() {
        val result = MarkdownEditCommands.heading("", 0, 0, level = 2)
        assertEquals("## ", result.text)
    }

    @Test
    fun unorderedListOnEmptyLineInsertsPrefix() {
        val result = MarkdownEditCommands.unorderedList("", 0, 0)
        assertEquals("- ", result.text)
    }

    @Test
    fun quoteOnEmptyLineInsertsPrefix() {
        val result = MarkdownEditCommands.quote("", 0, 0)
        assertEquals("> ", result.text)
    }

    @Test
    fun headingChangesExistingLevel() {
        val result = MarkdownEditCommands.heading("### title", 2, 3, level = 2)
        assertEquals("## title", result.text)
    }

    @Test
    fun quotePrefixesAllSelectedLines() {
        val result = MarkdownEditCommands.quote("a\nb\nc", 2, 3)
        assertEquals("a\n> b\nc", result.text)
    }

    @Test
    fun unorderedListTogglesAllSelectedLines() {
        val added = MarkdownEditCommands.unorderedList("a\nb", 0, 3)
        assertEquals("- a\n- b", added.text)

        val removed = MarkdownEditCommands.unorderedList("- a\n- b", 0, 7)
        assertEquals("a\nb", removed.text)
    }

    @Test
    fun orderedListNumbersLines() {
        val result = MarkdownEditCommands.orderedList("a\nb\nc", 2, 3)
        assertEquals("a\n1. b\nc", result.text)
    }

    @Test
    fun inlineCodeWrapsSelection() {
        val result = MarkdownEditCommands.inlineCode("a + b", 0, 5)
        assertEquals("`a + b`", result.text)
    }

    @Test
    fun fencedCodeWrapsSelection() {
        val result = MarkdownEditCommands.fencedCode("val a = 1", 0, 9)
        assertEquals("```\nval a = 1\n```", result.text)
    }

    @Test
    fun linkWrapsSelectionAndSelectsUrl() {
        val result = MarkdownEditCommands.link("site", 0, 4)
        assertEquals("[site](url)", result.text)
        assertEquals(7, result.selectionStart)
        assertEquals(10, result.selectionEnd)
    }

    @Test
    fun linkWithEmptySelectionSelectsLabel() {
        val result = MarkdownEditCommands.link("", 0, 0)
        assertEquals("[链接文字](url)", result.text)
        assertEquals(1, result.selectionStart)
        assertEquals(5, result.selectionEnd)
    }

    @Test
    fun horizontalRuleInsertsOnNewLine() {
        val result = MarkdownEditCommands.horizontalRule("abc", 3, 3)
        assertEquals("abc\n\n---\n", result.text)
    }
}
