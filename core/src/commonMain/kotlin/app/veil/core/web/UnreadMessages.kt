package app.veil.core.web

/**
 * Reads how many conversations have unread messages from the badge on the site's own Messages
 * link (present on every page of a signed-in user, even with the site's tab bar hidden). Only
 * the number is read, never names or message text.
 */
object UnreadMessages {
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

    /** The count from [SCRIPT]'s result, or null if the page couldn't tell. */
    fun parse(result: String?): Int? = result?.trim()?.toIntOrNull()?.takeIf { it >= 0 }

    /**
     * Whether [count] unread conversations deserve a notification, given the count the user was
     * last told about (or saw in the app): only when there are more than before.
     */
    fun isNews(count: Int, lastKnown: Int): Boolean = count > lastKnown

    /** Notification text; never a name or message content. */
    fun notificationText(count: Int): String = if (count == 1) "1 unread chat" else "$count unread chats"
}
