package dev.spendingtracker.server.order

import dev.spendingtracker.shared.api.OrderInput
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeUtility
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor
import java.util.Properties
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

/**
 * An order email, decoded: who sent it, its subject and date, and its body as [lines] of text (from the plain part,
 * or the HTML one with each block on its own line, trimmed, empty lines left out). [html] is the HTML part, for a
 * parser that needs its structure. A pasted text has only [lines].
 */
data class OrderEmail(
    val from: String,
    val subject: String,
    val sentAt: Instant?,
    val lines: List<String>,
    val html: String?,
)

/**
 * Reads one shop's order emails. [shop] is stored on the orders it reads, so sending the same email again updates
 * the order instead of adding another.
 */
interface OrderParser {
    val shop: String

    /** The order in [email], or null when it isn't one of this shop's order emails. */
    fun parse(email: OrderEmail): OrderInput?
}

/** The parsers `POST /orders/email` tries, in order. */
val defaultOrderParsers: List<OrderParser> = listOf(AlzaParser)

/**
 * Decodes [raw]: a MIME message (an `.eml` file, recognised by its headers) or otherwise plain text pasted from a
 * mail app.
 */
fun readOrderEmail(raw: String): OrderEmail {
    if (!looksLikeMime(raw)) return OrderEmail("", "", null, textLines(raw), null)
    // Sent as JSON text, so the client decoded the file as UTF-8: MIME bodies are ASCII (base64, quoted-printable)
    // or, as 8-bit, UTF-8 in practice.
    val message = MimeMessage(Session.getInstance(Properties()), raw.byteInputStream(Charsets.UTF_8))
    val from = (message.from?.firstOrNull() as? InternetAddress)?.address.orEmpty()
    val subject = message.getHeader("Subject", null)?.let { MimeUtility.decodeText(MimeUtility.unfold(it)) }.orEmpty()
    val parts = mutableListOf<Pair<String, String>>()
    collectText(message, parts)
    val html = parts.firstOrNull { it.first == "text/html" }?.second
    val plain = parts.firstOrNull { it.first == "text/plain" }?.second
    val lines = when {
        html != null -> htmlLines(html)
        plain != null -> textLines(plain)
        else -> emptyList()
    }
    return OrderEmail(from, subject, message.sentDate?.toInstant()?.toKotlinInstant(), lines, html)
}

private val HEADER = Regex("^[A-Za-z][A-Za-z0-9-]*:", RegexOption.MULTILINE)

private fun looksLikeMime(raw: String): Boolean {
    val head = raw.substringBefore("\n\n").substringBefore("\r\n\r\n")
    val names = HEADER.findAll(head).map { it.value.lowercase() }.toSet()
    return "content-type:" in names || ("from:" in names && "subject:" in names)
}

/** The text parts of [part], depth first, as (type, decoded text). Attachments are left out. */
private fun collectText(part: Part, into: MutableList<Pair<String, String>>) {
    when {
        part.isMimeType("multipart/*") -> {
            val multipart = part.content as Multipart
            for (i in 0 until multipart.count) collectText(multipart.getBodyPart(i), into)
        }
        Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) -> Unit
        part.isMimeType("text/html") -> into += "text/html" to part.content.toString()
        part.isMimeType("text/plain") -> into += "text/plain" to part.content.toString()
        part.isMimeType("message/rfc822") -> collectText(part.content as Part, into)
    }
}

private fun textLines(text: String): List<String> =
    text.lines().map { it.replace(' ', ' ').trim() }.filter { it.isNotEmpty() }

private val BLOCKS = setOf(
    "br", "p", "div", "tr", "td", "th", "li", "ul", "ol", "table", "tbody", "thead", "h1", "h2", "h3", "h4", "h5", "h6",
    "section", "article", "header", "footer", "center", "blockquote", "hr",
)

/** The HTML's visible text with each block element on its own line; styles, scripts and comments left out. */
fun htmlLines(html: String): List<String> {
    val document = Jsoup.parse(html)
    document.select("style, script, head, title").remove()
    val out = StringBuilder()
    document.body().traverse(object : NodeVisitor {
        override fun head(node: Node, depth: Int) {
            when {
                node is TextNode -> out.append(node.wholeText.replace(Regex("\\s+"), " "))
                node is Element && node.normalName() in BLOCKS -> out.append('\n')
            }
        }

        override fun tail(node: Node, depth: Int) {
            if (node is Element && node.normalName() in BLOCKS) out.append('\n')
        }
    })
    return textLines(out.toString())
}
