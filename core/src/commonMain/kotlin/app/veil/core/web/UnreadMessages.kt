package app.veil.core.web

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** An unread chat as the inbox lists it: who it is with and its latest message, as shown there. */
@Serializable
data class UnreadChat(val name: String, val preview: String = "")

/**
 * Reads how many conversations have unread messages from the badge on the site's own Messages
 * link (present on every page of a signed-in user, even with the site's tab bar hidden), and
 * which ones from the inbox list.
 */
object UnreadMessages {
    /** The inbox list; opening it marks nothing as read (only opening a chat does). */
    const val INBOX_PATH = "/direct/inbox/"

    /** Most chats named in one notification. */
    const val MAX_CHATS = 5

    /**
     * Answers the badge's number, 1 for a badge without a number, 0 without a badge, or -1 if the
     * page has no Messages link (not signed in, or not rendered yet).
     */
    const val SCRIPT: String = """
(function () {
  var link = document.querySelector('a[href^="/direct/inbox"]');
  if (!link) return -1;
  var digits = (link.innerText || '').replace(/[^0-9]/g, '');
  if (digits) return Math.min(parseInt(digits, 10), 999);
  var badge = link.querySelector('[data-visualcompletion="ignore"]');
  return badge && badge.childElementCount > 0 ? 1 : 0;
})()
"""

    /**
     * Run on the inbox list: the unread chats from the top as `[{name, preview}]` (up to
     * [MAX_CHATS]), or null while the list hasn't rendered. A chat row is a button with a time
     * stamp; unread ones show bold text or the blue dot. Reads only, never clicks.
     */
    const val CHATS_SCRIPT: String = """
(function () {
  var rows = Array.prototype.filter.call(document.querySelectorAll('div[role="button"]'), function (r) {
    return r.querySelector('abbr');
  });
  if (!rows.length) return null;
  function unread(r) {
    var all = r.querySelectorAll('span, div');
    for (var i = 0; i < all.length; i++) {
      var e = all[i], s = getComputedStyle(e);
      if (s.backgroundColor === 'rgb(0, 149, 246)' && e.offsetWidth > 0 && e.offsetWidth <= 16) return true;
      if (e.childElementCount === 0 && e.textContent.trim() && parseInt(s.fontWeight, 10) >= 600) return true;
    }
    return false;
  }
  var out = [];
  rows.forEach(function (r) {
    if (out.length >= 5 || !unread(r)) return;
    var lines = (r.innerText || '').split('\n').map(function (l) { return l.trim(); })
      .filter(function (l) { return l && l !== '·' && l !== '\u00b7'; });
    if (lines.length) out.push({ name: lines[0].slice(0, 60), preview: (lines[1] || '').slice(0, 120) });
  });
  return out;
})()
"""

    private val json = Json { ignoreUnknownKeys = true }

    /** The chats from [CHATS_SCRIPT]'s result; null if the list wasn't there (yet). */
    fun parseChats(result: String?): List<UnreadChat>? {
        val text = result?.trim()
        if (text.isNullOrEmpty() || text == "null") return null
        return runCatching { json.decodeFromString(ListSerializer(UnreadChat.serializer()), text) }.getOrNull()
            ?.filter { it.name.isNotBlank() }
    }

    /** The count from [SCRIPT]'s result, or null if the page couldn't tell. */
    fun parse(result: String?): Int? = result?.trim()?.toIntOrNull()?.takeIf { it >= 0 }

    /**
     * Whether [count] unread conversations deserve a notification, given the count the user was
     * last told about (or saw in the app): only when there are more than before.
     */
    fun isNews(count: Int, lastKnown: Int): Boolean = count > lastKnown

    /** The count alone (also the lock-screen version of the notification). */
    fun notificationText(count: Int): String = if (count == 1) "1 unread chat" else "$count unread chats"

    /** Notification title: who wrote ("Anna", "Anna and Ben", "Anna, Ben and 2 more"), or the count. */
    fun senders(count: Int, chats: List<UnreadChat>): String {
        if (chats.isEmpty()) return notificationText(count)
        val names = chats.map { it.name }.take(2)
        val more = maxOf(count, chats.size) - names.size
        return when {
            more > 0 -> names.joinToString(", ") + " and $more more"
            names.size == 2 -> "${names[0]} and ${names[1]}"
            else -> names[0]
        }
    }
}
