package dev.spendingtracker.server

import dev.spendingtracker.server.order.readOrderEmail
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Real order emails, kept on this machine only in `data/order-emails/<shop>/` (gitignored: they hold household data).
 * Without them this test checks nothing. Shop parsers add their own checks against the same files.
 */
class OrderEmailFixturesTest {
    @Test
    fun everyFixtureDecodesToText() {
        val emails = orderEmailFixtures()
        for (file in emails) {
            val email = readOrderEmail(file.readText())
            assertTrue(email.lines.size > 10, "${file.name}: only ${email.lines.size} lines")
            assertTrue(email.subject.isNotEmpty() && email.from.contains('@'), "${file.name}: no subject or sender")
            // The order number in the subject is in the body too: the body decoded.
            val number = Regex("\\d{8,}").find(email.subject)?.value
            if (number != null) assertTrue(email.lines.any { number in it }, "${file.name}: order number not in the body")
        }
    }
}

/** The fixtures of [shop] (or every shop), or none on a machine without them. */
fun orderEmailFixtures(shop: String? = null): List<File> {
    val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "data/order-emails") }
        .firstOrNull { it.isDirectory } ?: return emptyList()
    val dir = if (shop == null) root else File(root, shop)
    return dir.walkTopDown().filter { it.isFile && it.extension == "eml" }.sorted().toList()
}
