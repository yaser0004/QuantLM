package com.quantlm.yaser.presentation.chat

import com.quantlm.yaser.domain.model.Message
import org.junit.Assert.assertEquals
import org.junit.Test

/** Guards the pure regenerate-versioning logic (ChatGPT-style ‹k/n› toggle). */
class MessageVersioningTest {

    private fun user(id: Long, ts: Long = id) =
        Message(id = id, conversationId = 1, content = "u$id", isUser = true, timestamp = ts)

    private fun answer(id: Long, parent: Long?, active: Boolean, ts: Long = id) =
        Message(
            id = id, conversationId = 1, content = "a$id", isUser = false, timestamp = ts,
            parentMessageId = parent, isActiveVersion = active,
        )

    private fun marker(id: Long, ts: Long = id) =
        Message(id = id, conversationId = 1, content = "", isUser = false, timestamp = ts, isModelChangeMarker = true)

    @Test
    fun singleAnswer_hasNoToggle() {
        val out = ChatViewModel.projectActiveVersions(listOf(user(1), answer(2, parent = 1, active = true)))
        assertEquals(listOf(1L, 2L), out.map { it.id })
        val a = out.last()
        assertEquals(1, a.versionCount)
        assertEquals(0, a.versionIndex)
    }

    @Test
    fun threeVersions_onlyActiveShown_withCorrectIndex() {
        val all = listOf(
            user(1),
            answer(2, parent = 1, active = false, ts = 10),
            answer(3, parent = 1, active = true, ts = 20),
            answer(4, parent = 1, active = false, ts = 30),
        )
        val out = ChatViewModel.projectActiveVersions(all)
        assertEquals(listOf(1L, 3L), out.map { it.id }) // only the active answer + the user msg
        val a = out.first { !it.isUser }
        assertEquals(3, a.versionCount)
        assertEquals(1, a.versionIndex) // ordered by ts: [2,3,4] → active id=3 is index 1
    }

    @Test
    fun usersAndMarkers_untouched() {
        val out = ChatViewModel.projectActiveVersions(listOf(user(1), marker(9, ts = 5), answer(2, parent = 1, active = true)))
        assertEquals(listOf(1L, 9L, 2L), out.map { it.id })
        assertEquals(1, out.first { it.id == 1L }.versionCount)
        assertEquals(1, out.first { it.id == 9L }.versionCount)
    }

    @Test
    fun answerMessagesFor_latestTurn_multiSibling() {
        val all = listOf(user(1), answer(2, parent = 1, active = false), answer(3, parent = 1, active = true))
        assertEquals(listOf(2L, 3L), ChatViewModel.answerMessagesFor(all, all.first()).map { it.id })
    }

    @Test
    fun answerMessagesFor_legacyNullParent_positional() {
        val all = listOf(user(1), answer(2, parent = null, active = true))
        assertEquals(listOf(2L), ChatViewModel.answerMessagesFor(all, all.first()).map { it.id })
    }

    @Test
    fun answerMessagesFor_stopsAtNextUser_excludesMarker() {
        val all = listOf(
            user(1), marker(5, ts = 100), answer(2, parent = 1, active = true),
            user(3), answer(4, parent = 3, active = true),
        )
        // Only turn 1's answer; the next user msg is the boundary, the marker is excluded.
        assertEquals(listOf(2L), ChatViewModel.answerMessagesFor(all, all.first { it.id == 1L }).map { it.id })
    }
}
